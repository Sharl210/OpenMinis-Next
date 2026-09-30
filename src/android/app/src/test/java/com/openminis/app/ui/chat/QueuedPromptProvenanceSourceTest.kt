package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.MessagePartsCodec
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.repository.ChatRepository
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-queued-prompt-is-human] Which provenance the queued-prompt paths
 * stamp, and what that choice does to the row that reaches the database.
 *
 * The bug: the queued-prompt paths — `enqueuePrompt`,
 * `injectQueuedPromptsAsNewTurn` and `drainQueuedPrompts` — persisted and
 * rendered text the HUMAN typed, yet stamped it `TOOL_INJECTION`. Because
 * `ChatMessage.isManualHumanUser()` is `role == "user" && provenance ==
 * MANUAL_USER`, that made the four-button "previous / next human message"
 * navigation walk straight past every queued turn, and the app layer classified
 * a human turn as a synthetic one.
 *
 * The rule, stated once so call sites can be checked mechanically:
 *   MANUAL_USER     — the human authored this text (typed, queued, edited).
 *   TOOL_INJECTION  — the app synthesised the row with no human turn: tool
 *                     results (`persistToolResultMessage`) and the
 *                     `<system-reminder>` re-entry written by `resume()`.
 *
 * ## The two halves, and which one is executed
 *
 * **Executed (A):** the consequence. The tests below push the queued path's own
 * shape through the PRODUCTION write path — `ChatRepository.appendMessage` with
 * `provenance = MessageProvenance.MANUAL_USER` — and then read the persisted row
 * back: the marker it carries, the body it kept, and whether the row counts as a
 * human turn. This is the half that `MessageProvenanceTest` does not touch (it
 * exercises the codec and the predicate directly, never the repository), and it
 * is the half that decides whether the fix works at all: a call site can pass
 * MANUAL_USER and still lose the turn if the write path drops it. The
 * counter-case (TOOL_INJECTION, and a body with no marker at all) is asserted on
 * the same fixtures, so the two classes cannot drift into each other.
 *
 * **Structural (C):** which provenance each of the three `ChatViewModel` call
 * sites passes. Those call sites cannot be executed here, and this is the honest
 * reason rather than a preference: `enqueuePrompt` refuses to run unless
 * `_isStreaming.value` is true — private state only a live agent loop sets —
 * and `injectQueuedPromptsAsNewTurn` / `drainQueuedPrompts` are private suspend
 * functions reached from that loop, needing a live provider stack, a Room
 * database and an Android `Context`; this module's unit-test source set has no
 * Robolectric and no instrumentation, and no test in the repo constructs a
 * `ChatViewModel` at all. They are kept, with the reason on each assertion,
 * because deleting them would remove the only guard that a new queued call site
 * is not stamped `TOOL_INJECTION`.
 */
class QueuedPromptProvenanceSourceTest {

    // ------------------------------------------------------------- (A) executed

    /**
     * A [ChatDao] recording the row the write path builds. A JDK proxy rather
     * than a hand-written fake: `ChatDao` is a large Room interface and the
     * subject here is the row `appendMessage` produces, not a re-implementation
     * of Room. Any call outside the ones the write path makes fails loudly.
     */
    private class RecordingChatDao : InvocationHandler {
        val rows = mutableListOf<MessageEntity>()
        private var sortOrder = 0

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            this,
        ) as ChatDao

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val raw = args?.toList().orEmpty()
            // A suspend DAO method carries a trailing Continuation; this fake
            // answers synchronously, so the continuation is only stripped.
            val arguments = if (raw.lastOrNull() is kotlin.coroutines.Continuation<*>) raw.dropLast(1) else raw
            return when (method.name) {
                "nextSortOrder" -> sortOrder++
                "insertMessage" -> {
                    rows += arguments[0] as MessageEntity
                    Unit
                }
                "updateLastMessage", "touchSession" -> Unit
                "toString" -> "RecordingChatDao(${rows.size} rows)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error(
                    "RecordingChatDao: unexpected ${method.name}(...). The write path reached for a DAO " +
                        "method this fake does not model — model it explicitly instead of letting it pass silently.",
                )
            }
        }
    }

    private val sessionId = "session-queued-prompt"

    /** The body `drainQueuedPrompts` builds for a queued prompt: the human's text. */
    private fun queuedParts(text: String) = """[{"type":"text","value":${JSONObject.quote(text)}}]"""

    /** The row the queue path asks for, read the way the UI reads it. */
    private fun uiMessage(row: MessageEntity) = ChatMessage(
        id = row.id,
        role = row.role,
        content = "queued text",
        provenance = MessagePartsCodec.provenanceOf(row.partsJson),
    )

    private suspend fun write(text: String, provenance: MessageProvenance): MessageEntity {
        val fake = RecordingChatDao()
        val repository = ChatRepository(fake.dao)
        val returned = repository.appendMessage(
            sessionId = sessionId,
            role = "user",
            partsJson = queuedParts(text),
            provenance = provenance,
        )
        assertEquals("the write path must report the row it stored", fake.rows.single().id, returned.id)
        return fake.rows.single()
    }

    @Test
    fun `a queued prompt written as the human's turn counts as a human turn when the row comes back`() = runBlocking {
        val row = write("queued by the human", MessageProvenance.MANUAL_USER)

        // The marker has to survive the write path: a call site passing
        // MANUAL_USER is worth nothing if the repository drops it.
        assertEquals(
            "the persisted row must carry the manual-user marker",
            MessageProvenance.MANUAL_USER,
            MessagePartsCodec.provenanceOf(row.partsJson),
        )
        assertTrue(
            "a queued prompt is a human turn, so the previous/next navigation must find it",
            uiMessage(row).isManualHumanUser(),
        )

        // …and the marker is additive: the human's text is still the row's body.
        assertEquals(
            "adding the marker must not rewrite the message body",
            queuedParts("queued by the human"),
            MessagePartsCodec.withoutProvenance(row.partsJson),
        )
        assertEquals("user", row.role)
    }

    @Test
    fun `the same row stamped as an app-synthesised injection is walked past`() = runBlocking {
        // The defect shape, on identical text: this is what made every queued
        // turn invisible to the navigation.
        val row = write("queued by the human", MessageProvenance.TOOL_INJECTION)

        assertEquals(
            MessageProvenance.TOOL_INJECTION,
            MessagePartsCodec.provenanceOf(row.partsJson),
        )
        assertFalse(
            "an app-synthesised row must not be counted as the human speaking",
            uiMessage(row).isManualHumanUser(),
        )
    }

    @Test
    fun `a row written before provenance existed is not silently promoted to a human turn`() = runBlocking {
        // `appendMessage` defaults to UNKNOWN and, for a body that already carries
        // a marker, keeps the body's marker instead of overwriting it. Both halves
        // matter: an unmarked legacy row must stay unmarked (the navigation has a
        // separate rule for it), and a marker already in the body must not be
        // re-stamped by the default argument.
        val legacy = write("typed before provenance", MessageProvenance.UNKNOWN)
        assertEquals(
            MessageProvenance.UNKNOWN,
            MessagePartsCodec.provenanceOf(legacy.partsJson),
        )
        assertFalse(uiMessage(legacy).isManualHumanUser())

        // A body that ALREADY carries the marker keeps it when the caller passes
        // the default — the repository resolves UNKNOWN from the body rather than
        // erasing it.
        val fake = RecordingChatDao()
        val repository = ChatRepository(fake.dao)
        val preMarked = MessagePartsCodec.withProvenance(
            queuedParts("already marked"),
            MessageProvenance.MANUAL_USER,
        )
        repository.appendMessage(sessionId = sessionId, role = "user", partsJson = preMarked)
        val row = fake.rows.single()
        assertEquals(
            "an existing marker must not be erased by the default provenance argument",
            MessageProvenance.MANUAL_USER,
            MessagePartsCodec.provenanceOf(row.partsJson),
        )
        assertTrue(uiMessage(row).isManualHumanUser())
    }

    // ----------------------------------------------------- (C) kept, with reasons

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ChatViewModel.kt not found; run from the module or repo root")
    }

    /**
     * Only assignments in real code, not the prose in comments — otherwise the
     * explanatory comments added alongside this fix would satisfy or break the
     * counts on their own.
     */
    private fun provenanceAssignments(name: String): List<Int> =
        source.lines().withIndex()
            .filter { (_, line) ->
                val trimmed = line.trim()
                !trimmed.startsWith("//") &&
                    !trimmed.startsWith("*") &&
                    Regex("""provenance\s*=\s*MessageProvenance\.$name\b""").containsMatchIn(line)
            }
            .map { it.index + 1 }

    /**
     * (C) STRUCTURAL. Enumerates the owners that may stamp `TOOL_INJECTION`.
     * Executing any of them needs a `ChatViewModel` (see the class comment), so
     * what this keeps is the invariant a reviewer can check by reading: only the
     * app-synthesis owners may use that provenance, and a queued prompt —
     * the human's own text — may not.
     *
     * ## This list is a whitelist, and its cost is known
     *
     * The owners are matched by FUNCTION NAME, so a pure rename turns this red
     * with no defect present (it already did once: `resume` grew a thin wrapper
     * and the body moved into `resumeRun`). That cost is accepted deliberately,
     * because the alternative — no guard at all — is what let the original
     * regression land: a queued prompt stamped `TOOL_INJECTION` makes
     * `isManualHumanUser()` false and the four-button navigation walks straight
     * past the human's own turn.
     *
     * Maintenance contract, so the next person does not resolve the red by
     * weakening the check: when a legitimate NEW app-synthesis site appears (a row
     * the app writes with no human turn), confirm what body it persists and then
     * add its owner here with a one-line reason. Never delete the assertion, and
     * never reduce it to "at least one site exists" — that is satisfied by any
     * file that mentions the constant once.
     */
    @Test
    fun `tool injection is reserved for rows the app synthesises without a human turn`() {
        val sites = provenanceAssignments("TOOL_INJECTION")
        // (C): a whole-file structural fact — "the app-synthesis provenance is used
        // at least once". Not a behaviour, and there is no reachable behaviour to
        // attach it to.
        assertTrue("TOOL_INJECTION must have at least one site", sites.isNotEmpty())

        // Enumerate the owners that may stamp TOOL_INJECTION rather than pinning
        // how many there are. The contract stated above is "the app synthesised
        // the row with no human turn", and that set can legitimately grow — the
        // delegated-child abnormal-end reminder carries a `<system-reminder>` on
        // a row the app writes, exactly like resume()'s re-entry. A magic count
        // would have flagged that legitimate addition as a violation while
        // passing a new site added to some unrelated function that happened to
        // keep the total the same; checking the owner catches both.
        val sanctioned = setOf(
            "persistToolResultMessage",
            "resume",
            // The body of `resume` was extracted so `/goal`'s unattended
            // auto-continuation re-enters the agent loop through the same code
            // path. The `<system-reminder>` re-entry stamp moved into the shared
            // body with it, so the owner of that stamp is this function now —
            // `resume()` is a thin wrapper and stamps nothing itself.
            "resumeRun",
            "dispatchDelegationCommand",
            // The delegated-child abnormal-end reminder: same `<system-reminder>`
            // on a row the app writes, which is exactly the legitimate growth the
            // paragraph above describes.
            "publishDelegatedChildOutcome",
            // The inter-agent runtime mailbox reminder (child → parent message
            // delivery). Same mechanism, same provenance, same reason: the app
            // wrote the row and no human turn happened.
            "appendRuntimeMailboxReminder",
        )
        sites.forEach { line ->
            val owner = enclosingFunctionName(line)
            // (C): attributes a stamp to its owning function by reading the file.
            // The owner is not observable from any executed value, because the
            // functions themselves cannot be executed here.
            assertTrue(
                "TOOL_INJECTION at line $line is stamped inside '${owner ?: "<none>"}', which is " +
                    "not a sanctioned app-synthesis owner $sanctioned. A queued prompt is the " +
                    "human's own text and belongs to MANUAL_USER.",
                owner in sanctioned,
            )
        }

        val resumeLine = source.indexOf("fun resume()")
            .takeIf { it > 0 }
            ?.let { lineOf(it) }
            ?: 0
        // (C): a declaration-existence fact — `resume()` must still be declared,
        // so the assertion below has a meaningful reference point. The behaviour
        // `resume()` performs (re-entering the loop with a `<system-reminder>`) is
        // covered by `SystemRowDispatchTest.an injected row is not a human turn and
        // not a navigation anchor`, which asserts the TOOL_INJECTION row is neither
        // a human turn nor a navigation anchor.
        assertTrue("resume() declaration not found in ChatViewModel.kt", resumeLine > 0)
        // (C): the location of a statement inside the file. A behaviour test for
        // "resume() persists its re-entry as TOOL_INJECTION" would need a live
        // ChatViewModel + Room; `SystemRowDispatchTest` covers the row CLASS, not
        // this call site.
        assertTrue(
            "at least one TOOL_INJECTION stamp must sit after the resume() " +
                "declaration (the <system-reminder> re-entry); resumeLine=$resumeLine lines=$sites",
            sites.any { it > resumeLine },
        )
    }

    /**
     * The name of the nearest top-level `fun` declared above [line] (1-based).
     * Used to attribute a provenance stamp to the function that owns it.
     */
    private fun enclosingFunctionName(line: Int): String? {
        val lines = source.lines()
        for (index in (line - 2) downTo 0) {
            val match = TOP_LEVEL_FUN.find(lines[index]) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /**
     * (C) STRUCTURAL. The queued-prompt call sites. `enqueuePrompt` refuses to run
     * unless `_isStreaming.value` is true (private state only a live agent loop
     * sets), and the other two are private suspend functions reached from that
     * loop; no test in this repo constructs a `ChatViewModel`, and this module has
     * no Robolectric. The executed half of this file shows what the stamps mean
     * once they reach the database; this half keeps the stamps themselves from
     * regressing.
     */
    @Test
    fun `queued prompt paths stamp manual user`() {
        for (fn in listOf("fun enqueuePrompt(", "fun injectQueuedPromptsAsNewTurn(", "fun drainQueuedPrompts(")) {
            val start = source.indexOf(fn)
            // (C): the call site still exists. Without it the two assertions below
            // would pass vacuously on an empty slice.
            assertTrue("$fn not found in ChatViewModel.kt", start > 0)
            val body = functionBodyAt(start)
            // (C): the stamp the call site passes. Executing it needs the live
            // ViewModel described above; what it DOES once passed is covered by
            // `a queued prompt written as the human's turn counts as a human turn
            // when the row comes back`.
            assertTrue(
                "$fn must stamp MANUAL_USER for the human-authored queued prompt; body had no such stamp",
                Regex("""provenance\s*=\s*MessageProvenance\.MANUAL_USER\b""").containsMatchIn(body),
            )
            // (C): the regression guard — the stamp that used to be here. Same
            // reachability reason as above.
            assertTrue(
                "$fn must not stamp TOOL_INJECTION (that made isManualHumanUser() false for queued turns)",
                !Regex("""provenance\s*=\s*MessageProvenance\.TOOL_INJECTION\b""").containsMatchIn(body),
            )
        }
    }

    private fun lineOf(charOffset: Int): Int = source.take(charOffset).count { it == '\n' } + 1

    /**
     * Slice from a declaration to the next top-level member declaration, i.e.
     * a new declaration starting at 4-space indentation. Bounded so a missing
     * match cannot silently swallow the whole file.
     */
    private fun functionBodyAt(startIndex: Int): String {
        val nextMember = NEXT_MEMBER.find(source, startIndex + 1)?.range?.first ?: source.length
        return source.substring(startIndex, nextMember)
    }

    private companion object {
        val NEXT_MEMBER = Regex("""\n {4}(?:@\w+\s*\n {4})?(?:private |internal |public )?(?:suspend )?fun """)

        /** A top-level member function declaration, grouped on its name. */
        val TOP_LEVEL_FUN =
            Regex("""^ {4}(?:private |internal |public |protected )?(?:suspend )?fun ([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
