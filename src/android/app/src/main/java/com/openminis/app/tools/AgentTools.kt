package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.browser.BrowserTabPool

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        goalActive: Boolean = false,
        // [T-android-child-restart] Whether `restart_descendant` is advertised to
        // the model at all.
        //
        // Defaults to false, and that default is the honest one: it means
        // "nothing here says a restart can run". This file's own
        // `makeChildAgentTools` KDoc states the rule — "a stub is worse than
        // absence, because the model would burn turns calling something that
        // cannot work" — and the existing gates follow it (`goalActive` for
        // `goal_complete`, `memoryEnabled` for the memory tools).
        // `restart_descendant` gets the same treatment rather than an exception.
        //
        // Who supplies it: the app, as a CRITERION rather than a constant — the
        // real caller (`ChatViewModel.agentTools` → `mainAgentTools`) passes
        // `RuntimeSessionCoordinator.hasRestartableDescendant(actorSessionId)`,
        // i.e. "this session's own subtree holds a child the tree says can be
        // restarted". Two consequences worth stating, because both were defects
        // of exactly this kind:
        //
        //  * a constant `true` would be wrong — the requirement is narrower than
        //    "always available" (a child is a restart candidate only once it
        //    stopped), and offering the tool with nothing to restart spends model
        //    turns on calls that can only be refused;
        //  * a `false` that is left in place because the caller forgot the
        //    argument is ALSO wrong, and it is silent: the runtime transition, the
        //    authorization and the executor branch can all be finished and wired
        //    while the model still never sees the name. That happened here; the
        //    omission is now pinned by a test on the tool TABLE (see
        //    `MainAgentToolsRestartAvailabilityTest`), not by a comment.
        //
        // Independent of this flag,
        // [com.openminis.app.tools.AgentToolExecutor] refuses honestly when
        // dispatched without a launcher, so a bad caller cannot turn a missing
        // capability into a false success.
        restartAvailable: Boolean = false,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        add(browserUseDefinition())
        add(BrowserDevToolsTools.definition())
        add(stopDescendantDefinition())
        if (restartAvailable) add(restartDescendantDefinition())
        add(superviseDescendantsDefinition())
        add(messageChildDefinition())
        add(conversationQueryDefinition())
        add(communicationQueryDefinition())
        add(communicationDetailDefinition())
        add(deleteSubtreeDefinition())
        if (goalActive) add(goalCompleteDefinition())
        add(webSearchDefinition())
        add(webFetchDefinition())
        if (memoryEnabled) {
            add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }
    }

    /**
     * [T-android-child-agent-completion] Tool surface handed to a *delegated
     * child* agent by RuntimeChildRunner.
     *
     * Deliberately NOT a subset of [makeAgentTools]: the child runs without
     * any chat-UI scope (no tool blocks, no browser pool, no offload shell
     * bridge, no session bind mounts, no root-goal runtime), so a tool that
     * needs those would have to be stubbed — and a stub is worse than absence,
     * because the model would burn turns calling something that cannot work.
     * Every definition here is executable through
     * [AgentToolExecutor] or the runtime tree with nothing but a Context, the
     * child's own session id and the process-wide runtime coordinator.
     *
     * [SUBAGENT_COMPLETE_TOOL_NAME] is first on purpose: calling it is the
     * child's ONLY way to declare a natural end (see
     * `RuntimeChildRunner`), and the model reads tool order as a priority hint.
     *
     * The main agent must never receive this list — it has no delegated
     * sibling to end, and `subagent_complete` would be meaningless there.
     */
    fun makeChildAgentTools(): List<AgentToolDefinition> = buildList {
        add(subagentCompleteDefinition())
        add(superviseDescendantsDefinition())
        // [team-peer-mesh] request.md:5 — 「所有子代理可以相互沟通」. Without a
        // sender on the child surface the mesh had addresses and no dialler: the
        // runtime authorized teammate-to-teammate delivery, but no model could ask
        // for one. This is the producer half, and it is a CHILD tool because a
        // teammate IS a sibling — `message_child` (main-agent-only) addresses
        // descendants and would be the wrong name and the wrong relationship here.
        add(messagePeerDefinition())
        // [T-android-conversation-id-query] A delegated child gets the read tool
        // too. request.md:136 says the capability belongs to 「每一个对话，不管是主
        // 代理还是子代理还是相对主代理」 — and the requirement's own motivating
        // case is a child that needs to know what a conversation it has NO
        // ancestry with said (「如果这两个并没有血缘关系」). Withholding it from the
        // child would leave exactly that case unsolved.
        add(conversationQueryDefinition())
        add(webSearchDefinition())
        add(webFetchDefinition())
    }

    /**
     * [T-android-child-agent-completion] The delegated child's normal-end
     * signal. Distinct from [goalCompleteDefinition]'s `goal_complete`: that
     * one settles the single ROOT goal owned by the main agent, while this one
     * settles ONE delegated child node. Conflating them would let a child
     * close a goal it does not own.
     */
    const val SUBAGENT_COMPLETE_TOOL_NAME = "subagent_complete"

    private fun subagentCompleteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SUBAGENT_COMPLETE_TOOL_NAME,
        description = "Declare this delegated task FINISHED. Call it exactly once, as your last " +
            "action, only after the delegated request is actually satisfied and you have the " +
            "result the parent needs. This call is what marks your run as a natural " +
            "completion — ending your reply without it is recorded as an abnormal " +
            "termination, and the parent is told to decide whether to restart you.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Report delegated task complete'). Use the same language as the user."),
            "summary" to AgentToolParam("string", "The result the parent needs: what you did and what you found, concrete and actionable. Do not repeat your whole reply — this is the delivery line."),
        ),
        required = listOf("tool_title", "summary"),
        propertyOrdering = listOf("tool_title", "summary"),
    )

    private fun goalCompleteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "goal_complete",
        description = "Mark the active root goal as completed after its objective is actually satisfied.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for completing the goal."),
            "summary" to AgentToolParam("string", "A concise summary of the completed goal."),
        ),
        required = listOf("tool_title", "summary"),
        propertyOrdering = listOf("tool_title", "summary"),
    )
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Alpine Linux via PRoot). " +
            "The command runs via /bin/sh -c with stdout and stderr merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Default timeout is 15 minutes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900). Use a larger value for long-running commands like package installs."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        // [T-browser-capacity-20] Interpolate the pool's real ceiling instead of
        // spelling the number here. The old literal said 10 while MAX_TABS was
        // 10, so nothing looked wrong — until the ceiling changed and the model
        // was left being told a capacity the pool no longer had. A description
        // that reads the source of truth cannot drift.
        description = "Control a web browser with up to ${BrowserTabPool.MAX_TABS} tabs. " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, list_tabs to see all open tabs, and list_downloads to enumerate this session's downloads (state, size, source, originating tab/page and where each file landed). " +
             "Use get_history to read or delete browsing history, and list_bookmarks/get_bookmarks to list saved bookmarks. " +
             "Use add_bookmark/bookmark to save the current or supplied URL, remove_bookmark/unbookmark to cancel it, " +
             "delete_bookmark/clear_bookmarks to remove saved items, and open_bookmark/open_favorite to navigate to a saved item. " +
             "Use toggle_bookmark to press the toolbar star (saves when the page is not saved, removes it when it is) and is_bookmarked to read that star's current state for a URL or the open page. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to most recently used tab). Use list_tabs to see available tabs."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
             "item_id" to AgentToolParam("string", "History or bookmark id for delete/open/remove actions; a bookmark URL is also accepted."),
             "query" to AgentToolParam("string", "Optional text filter for get_history or bookmark listing actions."),
             "title" to AgentToolParam("string", "Optional bookmark title for add_bookmark/bookmark actions."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "item_id", "query", "title"),
    )

    private fun stopDescendantDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "stop_descendant",
        description = "Request the central runtime to stop a descendant agent. Authorization, ancestry, root, idempotency, receipt, and audit are enforced by the runtime tree; rejection is returned as failure.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this stop request."),
            "target_session_id" to AgentToolParam("string", "Target descendant session or runtime node ID."),
            "root_id" to AgentToolParam("string", "Optional expected runtime root ID."),
            "reason" to AgentToolParam("string", "Reason recorded in the runtime audit."),
            "operation_id" to AgentToolParam("string", "Optional idempotent operation ID."),
            "idempotency_key" to AgentToolParam("string", "Optional idempotency key."),
        ),
        required = listOf("tool_title", "target_session_id"),
        propertyOrdering = listOf("tool_title", "target_session_id", "root_id", "reason", "operation_id", "idempotency_key"),
    )

    private fun superviseDescendantsDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "supervise_descendants",
        // [T-android-supervise-conversation-id] request.md:132 asks this tool for
        // 「运行状况」 and each agent's 「哈希ID」. Both are named here so the model
        // knows what it is looking at without having to infer it: the ID field is
        // the app's own `minis-conv-` conversation id (the SAME id the user copies
        // and `conversation_query` accepts), and the liveness fields exist because
        // a bare status word cannot answer "is it actually working" — a node whose
        // owner died keeps saying RUNNING until its lease expires.
        description = "Read the current session's runtime node, capability snapshot, configuration " +
            "revision, and the status of every descendant in this session's tree. Call it " +
            "periodically as the orchestrator to check that your descendants are still doing what " +
            "you expect. Each node reports its conversation ID (`conversation_id`, the app's own " +
            "`minis-conv-` id — pass it to conversation_query to read that conversation), its " +
            "`status`, whether it stopped abnormally (`abnormal`), how long its lease has left " +
            "(`lease_remaining_ms`) and how long since it last wrote anything " +
            "(`last_activity_age_ms`) — the last three are what distinguish a node that is " +
            "genuinely working from one that is only still marked RUNNING. Each node also reports " +
            "`address`, the runtime's routing address for that conversation — NOT the same " +
            "thing as `conversation_id`, and only `conversation_id` can be read with. " +
            "Read-only and scoped to the current session.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this supervision query."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    /**
     * [T-android-child-restart] Bring one abnormally interrupted descendant back
     * to RUNNING.
     *
     * The actor is whatever session the application is bound to — there is
     * deliberately NO `actor_session_id` parameter, for the same reason
     * [superviseDescendantsDefinition] has none: the tool schema is not an
     * authorization boundary, so a model-supplied identity field could only ever
     * be a forgery attempt. `child_session_id` names the TARGET, never the
     * caller. Authored to match the iOS/Android control-tool shape
     * (`stop_descendant`, `delete_subtree`), whose `target_session_id` is
     * likewise target-only.
     *
     * Main-agent-only: a delegated child holds no subtree to restart, so this is
     * absent from `makeChildAgentTools()`.
     */
    private fun restartDescendantDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = RESTART_DESCENDANT_TOOL_NAME,
        description = "Restart a descendant agent this session owns whose runtime node ended " +
            "abnormally (for example after an app crash, where lease reconciliation marks " +
            "the child ABNORMAL_INTERRUPTION). Only terminal descendants can be restarted, " +
            "and the target must be inside this session's own runtime tree; anything else is " +
            "rejected as a failure rather than silently ignored. Call " +
            "supervise_descendants first to see which descendants are abnormal.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this restart request."),
            "child_session_id" to AgentToolParam("string", "The descendant session or runtime node ID to restart. This is the target — the acting session is always the current one and cannot be set here."),
            "reason" to AgentToolParam("string", "Optional reason recorded in the runtime audit, e.g. 'recover after crash'."),
        ),
        required = listOf("tool_title", "child_session_id"),
        propertyOrdering = listOf("tool_title", "child_session_id", "reason"),
    )

    /** Tool name for [restartDescendantDefinition]; shared with [AgentToolExecutor]. */
    const val RESTART_DESCENDANT_TOOL_NAME = "restart_descendant"

    /**
     * [T-android-agent-messaging] The main agent's SEND half of the parent/child
     * conversation.
     *
     * Why this tool exists at all: the runtime has carried parent→child messages
     * since the mailbox was built — `SessionTreeRuntime.send` enqueues into a
     * child's inbox, `RuntimeChildRunner` drains STEER/QUEUE/NOTIFY into the
     * child's next model call, and `RuntimeCommunicationGateway.send` wraps the
     * whole thing with authorization and a receipt. What was missing was a
     * PRODUCER: no tool could reach it, so nothing ever put a message in a
     * running child's inbox. The delivery machinery was complete and idle.
     *
     * Main-agent-only, exactly like [restartDescendantDefinition]: the target is
     * a descendant of the current session. A delegated child gets no such tool
     * (see `makeChildAgentTools`) — its outbound traffic is the result it returns
     * when it finishes.
     *
     * Same identity discipline as its neighbours: there is deliberately NO
     * `actor_session_id`, because the schema is documentation for the model and
     * not an authorization boundary — the acting session is always the one the
     * application bound.
     */
    private fun messageChildDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = MESSAGE_CHILD_TOOL_NAME,
        description = "Send one message to a sub-agent this session owns. Use it while the " +
            "sub-agent is working (to steer it), to hand it a follow-up instruction, or to tell " +
            "it something it needs to know. `delivery` picks how the message reaches it: " +
            "`steer` (the default) is absorbed at the sub-agent's NEXT model step, so the " +
            "sub-agent acts on it during the work it is already doing; the runtime downgrades it " +
            "to `queue` when the sub-agent is not currently running, and says so in the reply. " +
            "`queue` hands the message over as a separate next-turn instruction — use it when the " +
            "sub-agent is not mid-flight, or when the instruction must not interrupt the step in " +
            "progress. `notify` is a one-way notice read at the sub-agent's next step and asks for " +
            "no action; use it to hand over information only. " +
            "The target must be a descendant of this session — get the current list, and each " +
            "child's `conversation_id`, from `supervise_descendants`. Ancestry, the delivery edge " +
            "and the message size are enforced by the runtime tree, and a refusal is returned as a " +
            "failure carrying the runtime's own reason: when this tool reports a failure, the " +
            "message was NOT delivered.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Steer sub-agent to use the new API key', 'Queue follow-up for the research sub-agent'). Use the same language as the user."),
            "target_session_id" to AgentToolParam("string", "The sub-agent to message: its conversation ID (`minis-conv-…`) or its runtime node ID, both as reported by `supervise_descendants`. This is always the TARGET — the acting session is the current one and cannot be set here. It must be a descendant of this session."),
            "message" to AgentToolParam("string", "The message body the sub-agent will read. Write it as an instruction it can act on: it arrives in the sub-agent's own context, not in yours."),
            "delivery" to AgentToolParam(
                "string",
                "How the message reaches the sub-agent. `steer` (default): absorbed at its next " +
                    "model step, interrupting what it is doing — the right choice while it is " +
                    "working. `queue`: a separate next-turn instruction, read when it starts its " +
                    "next round, and it does not interrupt a step in progress — the right choice " +
                    "when the sub-agent is not running yet or must not be interrupted. `notify`: a " +
                    "one-way notice, no action requested.",
                enumValues = listOf(DELIVERY_STEER, DELIVERY_QUEUE, DELIVERY_NOTIFY),
            ),
        ),
        required = listOf("tool_title", "target_session_id", "message"),
        propertyOrdering = listOf("tool_title", "target_session_id", "message", "delivery"),
    )

    /** Tool name for [messageChildDefinition]; shared with [AgentToolExecutor]. */
    const val MESSAGE_CHILD_TOOL_NAME = "message_child"

    /** Tool name for [messagePeerDefinition]; shared with [AgentToolExecutor]. */
    const val MESSAGE_PEER_TOOL_NAME = "message_peer"

    /**
     * The wire spellings of the `delivery` argument. Declared as constants because
     * [AgentToolExecutor] has to parse exactly the strings the schema advertises —
     * a literal typed twice is a literal that can drift, and a drifted delivery
     * value would silently mean the wrong thing.
     */
    const val DELIVERY_STEER = "steer"
    const val DELIVERY_QUEUE = "queue"
    const val DELIVERY_NOTIFY = "notify"

    /**
     * [team-peer-mesh] Send one message to a Team PEER — a sibling sub-agent under
     * the same runtime root, not one of this session's own descendants.
     *
     * request.md:5 — 「所有子代理可以相互沟通」, and request.md:33 — 「团队模式的话，
     * 他们就是 P2P 的节点…从和从之间可以 P2P」. The runtime half of this already
     * existed and worked: joining a Team builds a bidirectional `TEAM_PEER` edge
     * between every pair of members, and `SessionTreeRuntime.send` authorizes peer
     * delivery off that edge. What did not exist was a SENDER — delegated children
     * were handed no messaging tool at all, so no model could ask for a
     * sibling-to-sibling message. This definition is that sender.
     *
     * Deliberately NOT `message_child`: the two relationships differ, and the name is
     * how the model tells them apart. `message_child` goes DOWN (target is a
     * descendant; `steer`/`queue`/`notify` choose when the message lands) and is
     * main-agent-only. This one goes SIDEWAYS between equals and carries exactly one
     * delivery class — `TEAM_PEER`, read at the peer's next model step — because a
     * peer holds no authority over another peer's step: the mesh grants SEND, never
     * STEER, which is what keeps the master/slave relation of request.md:33 intact.
     *
     * Authorization is neither described nor decided here. Same root, Team mode on
     * both endpoints, and an existing SEND-bearing peer edge are enforced by the
     * runtime tree, and a refusal comes back as a failure carrying the runtime's own
     * reason. The schema only tells the model what to name and what it will be told
     * when the answer is no.
     */
    private fun messagePeerDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = MESSAGE_PEER_TOOL_NAME,
        description = "Send one message to a PEER sub-agent — another member of the same Team, " +
            "not one of your own sub-agents. Use it when you need something from a teammate " +
            "working alongside you. The message lands in the peer's inbox and is read at its next " +
            "model step. " +
            "Name the peer with its `conversation_id` (`minis-conv-…`) or its runtime node ID, " +
            "both as `supervise_descendants` reports them. Only same-Team peers are reachable: a " +
            "session outside this team, or one in traditional sub-agent mode, is refused. A " +
            "failure is reported as a failure — when this tool says a message was not delivered, " +
            "nothing was queued.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Ask the QA sub-agent to re-run the tests'). Use the same language as the user."),
            "target_session_id" to AgentToolParam("string", "The peer sub-agent to message: its conversation ID (`minis-conv-…`) or its runtime node ID, both as reported by `supervise_descendants`. This is always the TARGET — the acting session is the current one and cannot be set here."),
            "message" to AgentToolParam("string", "The message body the peer will read. Write it as something a teammate can act on: it arrives in the peer's own context, not in yours."),
        ),
        required = listOf("tool_title", "target_session_id", "message"),
        propertyOrdering = listOf("tool_title", "target_session_id", "message"),
    )

    /**
     * [T-android-conversation-id-query] Read another conversation's content by
     * its conversation ID.
     *
     * The description is the model-facing half of the requirement's 「提示词工程」
     * (request.md:136): it has to say, in the tool surface itself, that the
     * `minis-conv-` prefix marks this app's own conversation IDs, that reading
     * needs no relationship and no handshake, and that the read is progressive.
     * The same three facts are also injected into the system prompt (see
     * `ConversationIdProtocol.systemInstruction`); stating them in both places is
     * deliberate, because a model may meet the ID in a tool result before it ever
     * re-reads its system prompt.
     */
    private fun conversationQueryDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "conversation_query",
        description = "Read the contents of any conversation in this app by its conversation ID — " +
            "the main agent's, a sub-agent's, or a related main agent's. IDs of this app's " +
            "conversations begin with `minis-conv-`; that prefix is how you recognise one. " +
            "This works for conversations you share no ancestry with, and needs no handshake: " +
            "having the ID is enough. Read-only. Results are one bounded page plus a " +
            "`next_cursor` — page or search incrementally rather than trying to pull a whole " +
            "conversation at once, because an unbounded dump is what floods your context window.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this read, shown to the user."),
            "conversation_id" to AgentToolParam("string", "The conversation to read, e.g. `minis-conv-<uuid>`. A bare id without the prefix is also accepted."),
            "query" to AgentToolParam("string", "Optional keyword search within that conversation. Omit to read it in order."),
            "cursor" to AgentToolParam("string", "Opaque `next_cursor` from a previous call. Omit for the first page."),
            "limit" to AgentToolParam("integer", "Page size. Defaults to ${ConversationQueryPolicy.defaultPageLimit()}; maximum ${ConversationQueryPolicy.maxPageLimit()}."),
            "result_budget_chars" to AgentToolParam("integer", "Maximum characters of message text to return. Defaults to ${ConversationQueryPolicy.defaultBudgetChars()}; everything over it comes back as a `next_cursor`."),
        ),
        required = listOf("tool_title", "conversation_id"),
        propertyOrdering = listOf("tool_title", "conversation_id", "query", "cursor", "limit", "result_budget_chars"),
    )

    private fun communicationQueryDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "communication_query",
        description = "Read metadata-only communication records visible to the current session. Runtime authorization scopes results to the current actor; supports pagination and bounded result budget.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this communication query."),
            "summary_contains" to AgentToolParam("string", "Optional summary filter."),
            "direction" to AgentToolParam("string", "Optional OUTBOUND or INBOUND filter."),
            "state" to AgentToolParam("string", "Optional QUEUED, DELIVERED, CLAIMED, FAILED, or REJECTED filter."),
            "limit" to AgentToolParam("integer", "Page size; runtime clamps to its safe limit."),
            "result_budget_chars" to AgentToolParam("integer", "Maximum serialized result budget."),
            "cursor" to AgentToolParam("string", "Opaque cursor returned by a previous query."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "summary_contains", "direction", "state", "limit", "result_budget_chars", "cursor"),
    )

    private fun communicationDetailDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "communication_detail",
        description = "Perform an explicit second-step metadata detail lookup for a communication record visible to the current session. Authorization remains runtime-scoped.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this communication detail lookup."),
            "record_id" to AgentToolParam("string", "Communication metadata record ID."),
        ),
        required = listOf("tool_title", "record_id"),
        propertyOrdering = listOf("tool_title", "record_id"),
    )
    private fun deleteSubtreeDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "delete_subtree",
        description = "Delete a completed descendant session and its entire runtime subtree. The current session is the trusted executor; self-delete and unrelated targets are rejected. Returns a structured receipt; the target's parent receives a separate runtime notification.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this deletion request."),
            "target_session_id" to AgentToolParam("string", "Target descendant session or runtime node ID."),
            "root_id" to AgentToolParam("string", "Optional expected runtime root ID."),
            "operation_id" to AgentToolParam("string", "Optional operation ID."),
            "idempotency_key" to AgentToolParam("string", "Optional idempotency key."),
        ),
        required = listOf("tool_title", "target_session_id"),
        propertyOrdering = listOf("tool_title", "target_session_id", "root_id", "operation_id", "idempotency_key"),
    )

    private fun webSearchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "web_search",
        description = "Search public web sources without a paid API key. Results are source data; do not bypass login, CAPTCHA, paywalls, access controls, or certificate validation. robots.txt is not a hard blocker.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this search."),
            "query" to AgentToolParam("string", "Search query."),
            "max_results" to AgentToolParam("integer", "Maximum results; server clamps to configured bounds."),
            "timeout_ms" to AgentToolParam("integer", "Overall timeout; server clamps to configured bounds."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results", "timeout_ms"),
    )

    private fun webFetchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "web_fetch",
        description = "Fetch bounded text from an HTTP(S) page. Server enforces timeout, response-size, redirect, and public-address checks; it never bypasses technical access controls.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise title for this fetch."),
            "url" to AgentToolParam("string", "HTTP(S) URL to fetch."),
            "timeout_ms" to AgentToolParam("integer", "Request timeout; server clamps to configured bounds."),
            "max_bytes" to AgentToolParam("integer", "Maximum response bytes; server clamps to configured bounds."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "timeout_ms", "max_bytes"),
    )
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )
}
