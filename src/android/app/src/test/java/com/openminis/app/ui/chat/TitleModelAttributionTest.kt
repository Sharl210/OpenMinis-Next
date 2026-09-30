package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.ChatViewModel.Companion.titleAttributionEntryId
import com.openminis.app.ui.chat.ChatViewModel.Companion.titleAttributionMatches
import com.openminis.app.ui.sessions.writeLadderTitle
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which model a generated title is RECORDED as having come from.
 *
 * This is the second half of R55's "标题生成模型…要设置" claim, and it is the half
 * that a config test cannot see. The setting deciding which model runs is one
 * fact; the session row naming that same model is another, and the two were
 * allowed to disagree:
 *
 *  - `generateSessionTitleIfNeeded` fell back to `currentProvider`, but set the
 *    attribution entry from the **Primary Agent Model** setting — a model that
 *    provider is not. Because `titleSnapshot` preferred any entry it was handed
 *    over the provider's own model, the session recorded the primary model while
 *    the session's current model produced the title.
 *
 * Both halves are pinned below: the policy that picks the entry
 * ([titleAttributionEntryId]) and the rule that an entry disagreeing with the
 * serving model may not be used at all ([titleAttributionMatches]). Reverting
 * either one to the old behaviour turns these red — see the probe in
 * `/tmp/r55r56-falsify.sh`.
 */
class TitleModelAttributionTest {

    // ── Which entry is attributed ────────────────────────────────────────

    @Test
    fun `a resolved title sub-model is the attribution`() {
        assertEquals(
            "title-entry",
            titleAttributionEntryId(resolvedSubEntryId = "title-entry", activeEntryId = "session-entry"),
        )
    }

    @Test
    fun `without a sub-model the session's own active entry is the attribution`() {
        // currentProvider — the provider the request actually goes out on — is
        // built from the active entry, so that is what answered.
        assertEquals(
            "session-entry",
            titleAttributionEntryId(resolvedSubEntryId = null, activeEntryId = "session-entry"),
        )
    }

    @Test
    fun `nothing is attributed when neither side resolves`() {
        assertNull(titleAttributionEntryId(resolvedSubEntryId = null, activeEntryId = null))
        assertNull(titleAttributionEntryId(resolvedSubEntryId = null, activeEntryId = ""))
    }

    @Test
    fun `the production fallback never attributes the primary model setting`() {
        val source = chatViewModelSource()
        val site = source.indexOf("titleRequestEntry = resolveRoleEntry(")
        assertTrue("the title attribution site must exist", site >= 0)
        // Scoped to THIS call, not the whole file: `activeEntryId =
        // _activeEntryId.value` occurs in several unrelated places, so a
        // file-wide `contains` would stay green even after the attribution site
        // was reverted — a test that cannot fail is not a test.
        val block = source.substring(
            site,
            source.indexOf("val provider = subProvider ?: currentProvider", site),
        )

        assertTrue(
            "the attribution must be chosen by the shared policy: $block",
            block.contains("titleAttributionEntryId("),
        )
        assertTrue(
            "…from the entry that actually answered the request: $block",
            block.contains("activeEntryId = _activeEntryId.value"),
        )
        assertTrue(
            "…and only from an entry the sub-model resolution really returned: $block",
            block.contains("resolvedSubEntryId = resolvedSubEntryId"),
        )
        assertFalse(
            "attributing the Primary Agent Model setting here recorded a model that never ran: $block",
            block.contains("primaryModelEntryId"),
        )
    }

    // ── Whether a candidate entry may be used at all ─────────────────────

    @Test
    fun `an entry for the model that served the request may be used`() {
        assertTrue(titleAttributionMatches(entryModelId = "gpt-5", servedModelId = "gpt-5"))
    }

    @Test
    fun `an entry for a different model may not be used`() {
        // The reported defect in one assertion: the entry named the primary
        // model, the provider was the session's model. Recording the entry would
        // name a model that never ran.
        assertFalse(
            "a disagreeing entry is worse than no entry — it is a wrong answer",
            titleAttributionMatches(entryModelId = "primary-model", servedModelId = "session-model"),
        )
    }

    @Test
    fun `an unknown or absent entry may not be used`() {
        assertFalse(titleAttributionMatches(entryModelId = null, servedModelId = "session-model"))
        assertFalse("an unknown serving model cannot confirm an entry", titleAttributionMatches(entryModelId = "gpt-5", servedModelId = null))
        assertFalse(titleAttributionMatches(entryModelId = null, servedModelId = null))
    }

    @Test
    fun `the snapshot uses the entry only when it agrees with the provider`() {
        val source = chatViewModelSource()
        val snapshotStart = source.indexOf("private fun titleSnapshot(")
        assertTrue("titleSnapshot must exist", snapshotStart >= 0)
        val snapshot = source.substring(snapshotStart, source.indexOf("private var titleGenerationInFlight", snapshotStart))

        assertTrue(
            "the entry must be checked against the model that answered: $snapshot",
            snapshot.contains("titleAttributionMatches("),
        )
        assertTrue(
            "and the provider's own model must be the comparison key",
            snapshot.contains("servedModelId = provider?.model?.id"),
        )
    }

    // ── The give-up paths must not attribute either ──────────────────────

    @Test
    fun `a title derived from the user's own words is not attributed to a model`() {
        // `applyFallbackTitleFromFirstMessage` names the session after the user's
        // first message, truncated. No model produced that title, so there is no
        // model to name — and a session row naming one that never ran is worse
        // than naming none. It used to hand a (write-only, never-cleared)
        // `titleRequestProvider` to `titleSnapshot`, which could even be left over
        // from an EARLIER dispatch. `titleSnapshot`'s own guard fixed the success
        // path; this is the give-up path, which never had one.
        val body = functionBody(
            chatViewModelSource(),
            "private suspend fun applyFallbackTitleFromFirstMessage(",
        )
        assertNotNull("applyFallbackTitleFromFirstMessage must exist", body)
        // Checked on CODE, not on prose: a comment documenting the removed
        // attribution mentions the field name, and a naive `contains` on the raw
        // body reads that documentation as the defect still being present.
        val text = code(body!!)
        assertFalse(
            "the give-up path named a model for a title no model produced: $body",
            text.contains("updateSessionTitleAndCategoryWithModelSnapshot"),
        )
        assertFalse(
            "…and it consulted a provider that is only ever assigned, never cleared: $body",
            text.contains("titleRequestProvider"),
        )
        assertTrue(
            "a derived title is written through the writer that records no model: $body",
            text.contains("updateSessionTitle(sidForCheck, fallbackTitle)"),
        )
    }

    @Test
    fun `the session list fallback is not attributed either`() {
        // The other unguarded writer, reached when EVERY candidate provider was
        // skipped (no instance, no usable key, or provider creation threw) — so
        // provably no model ran. It still wrote `entry.id` / `entry.model.id`
        // into the session row.
        val body = functionBody(sessionListViewModelSource(), "private suspend fun applyFallbackTitle(")
        assertNotNull("applyFallbackTitle must exist", body)
        val text = code(body!!)
        assertFalse(
            "the session list fallback attributed a model to a derived title: $body",
            text.contains("updateSessionTitleAndCategoryWithModelSnapshot"),
        )
        assertFalse(
            "…and it resolved an entry purely in order to name it: $body",
            text.contains("explicitTitleEntry"),
        )
        assertTrue(
            "a derived title is written through the writer that records no model: $body",
            text.contains("updateSessionTitle(id, cleaned)"),
        )
    }

    @Test
    fun `the success paths still attribute the model that really ran`() {
        // The guard above must not be "fixed" by deleting attribution outright:
        // a title a model DID produce should still be credited to it.
        val chat = chatViewModelSource()
        val snapshot = functionBody(chat, "private fun titleSnapshot(")
        assertNotNull(snapshot)
        assertTrue(
            "titleSnapshot must keep refusing an entry that disagrees with the serving model",
            snapshot!!.contains("titleAttributionMatches("),
        )
        val afterParse = chat.substringAfter("val (title, category, folderName) = parseTitleResponse(response.text)")
        assertTrue(
            "the success path must still record the model snapshot",
            afterParse.take(900).contains("updateSessionTitleAndCategoryWithModelSnapshot"),
        )
        // The session-list half of this test is now behaviour, not file text —
        // see `the session list records the model of the entry that wrote the
        // title` below. What it replaced could not fail: it searched for
        // `parseTitleResponse(response.text)` in SessionListViewModel.kt, where
        // that string does not occur (the file builds its parser as
        // `parse = { parseTitleResponse(it) }`), and Kotlin's `substringAfter`
        // returns the WHOLE string when the delimiter is missing — so
        // `.take(900)` read the file's import block and the assertion was false
        // from the day it was written.
    }

    // ── The session list's success path, executed ────────────────────────

    /**
     * The session list must RECORD the model that produced the title — the whole
     * claim, executed.
     *
     * This drives `writeLadderTitle` — the function
     * `SessionListViewModel.generateTitleFromStore` calls on
     * `TitleLadderOutcome.Written` — against a REAL `ChatRepository` over a
     * recording `ChatDao`, and asserts the row the database is actually handed.
     * So the thing under test is the value, not the presence of an identifier:
     * crediting the row to the session's current model, to the Primary Agent
     * Model setting, or to nothing at all each turn this red.
     *
     * Not executed, and said rather than implied: the ~15 lines of
     * `generateTitleFromStore` around that call. `SessionListViewModel` cannot be
     * constructed in this source set — no Robolectric, and its
     * `ProviderRepository` opens a Room database and reads `SharedPreferences` —
     * so the call itself is pinned separately, and labelled, in
     * `the session list success path routes through the recording writer` below.
     * Everything the call DOES is executed here.
     */
    @Test
    fun `the session list records the model of the entry that wrote the title`() = runTest {
        val dao = RecordingChatDao()
        val writer = entry(uuid = "title-entry", modelId = "claude-sonnet-4", displayName = "Sonnet 4")

        val recorded = writeLadderTitle(
            chatRepository = ChatRepository(dao.dao),
            sessionId = "session-1",
            entry = writer,
            title = "Debug Login Page Issue",
            category = "code",
            providerType = "ANTHROPIC",
            generatedAt = 1_700_000_000_000L,
        )

        assertTrue("a title a model produced must be credited to it", recorded)
        val row = dao.modelSnapshotWrites.single()
        assertEquals("session-1", row.sessionId)
        assertEquals("Debug Login Page Issue", row.title)
        assertEquals("code", row.category)
        assertEquals(
            "the row must name the entry that answered, not any other entry in the ladder",
            "title-entry",
            row.entryId,
        )
        assertEquals(
            "…and its model — not the session's current model, and not the Primary Agent Model setting",
            "claude-sonnet-4",
            row.modelId,
        )
        assertEquals("Sonnet 4", row.displayName)
        assertEquals("ANTHROPIC", row.providerType)
        assertEquals(1_700_000_000_000L, row.generatedAt)
        assertEquals(
            "exactly one write: a row credited twice is a row credited to one model too many",
            0,
            dao.plainWrites.size,
        )
    }

    /**
     * When the provider instance behind the answering entry is gone there is no
     * honest provider kind to name. The title must still be recorded — but with
     * NO attribution, not with a blank or guessed one: a snapshot column holding
     * "" reads as "a model named ''", which the session row would then display.
     */
    @Test
    fun `an unresolvable provider kind records the title without naming any model`() = runTest {
        val dao = RecordingChatDao()

        val recorded = writeLadderTitle(
            chatRepository = ChatRepository(dao.dao),
            sessionId = "session-1",
            entry = entry(uuid = "gone", modelId = "m", displayName = "M"),
            title = "Fallback Title",
            category = null,
            providerType = null,
        )

        assertFalse("no model can be named, so none may be recorded", recorded)
        assertEquals(0, dao.modelSnapshotWrites.size)
        val row = dao.plainWrites.single()
        assertEquals("the title must still reach the row", "Fallback Title", row.title)
        assertEquals("session-1", row.sessionId)
    }

    /**
     * (C) The wiring half — a source check, and it is one because it cannot be a
     * behaviour check in this module.
     *
     * `SessionListViewModel` needs an Android `Context` plus a
     * `ProviderRepository` (Room + `SharedPreferences`); this unit-test source set
     * has no Robolectric, so the ViewModel cannot be built and
     * `generateTitleFromStore` cannot be run. The behaviour behind the call IS
     * executed, above, against a real `ChatRepository`. What is left over — "the
     * success path still routes through that writer, instead of the attribution
     * having been deleted as a way to satisfy the two fallback guards" — is
     * checked on ONE function's body.
     *
     * Scoped to `generateTitleFromStore`, not to a character window after a marker
     * string: the assertion this replaces searched for a delimiter that does not
     * exist in the file, and `substringAfter` answers a missing delimiter with the
     * whole string, so it pinned nothing at all.
     */
    @Test
    fun `the session list success path routes through the recording writer`() {
        val body = functionBody(sessionListViewModelSource(), "private suspend fun generateTitleFromStore(")
        assertNotNull("generateTitleFromStore must exist", body)
        assertTrue(
            "the success path must record the model that really ran: ${code(body!!)}",
            code(body!!).contains("writeLadderTitle("),
        )
    }

    // ── Harness ──────────────────────────────────────────────────────────

    private fun entry(uuid: String, modelId: String, displayName: String) = ModelEntry(
        providerInstanceId = "provider-instance-1",
        baseModel = LLMModel(id = modelId, displayName = displayName, provider = "anthropic"),
        uuid = uuid,
    )

    /** The arguments of one title write, as the database would receive them. */
    private class TitleRow(
        val sessionId: String,
        val title: String,
        val category: String?,
        val entryId: String?,
        val modelId: String?,
        val displayName: String?,
        val providerType: String?,
        val generatedAt: Long?,
    )

    /**
     * A [ChatDao] that records the two title writers.
     *
     * A JDK proxy rather than a hand-written fake: `ChatDao` is a 69-method Room
     * interface and the subject here is the ARGUMENTS the write path hands the
     * database, not a re-implementation of Room. Any call outside the two modelled
     * writers fails loudly, so a write this test ought to know about cannot be
     * absorbed silently — the same shape
     * `MessageThinkingLevelPersistenceTest.RecordingChatDao` uses.
     */
    private class RecordingChatDao : InvocationHandler {
        val modelSnapshotWrites = mutableListOf<TitleRow>()
        val plainWrites = mutableListOf<TitleRow>()

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            this,
        ) as ChatDao

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val raw = args?.toList().orEmpty()
            // A suspend DAO method carries a trailing Continuation; this fake
            // answers synchronously, so it is only stripped off, never resumed —
            // resuming it as well would complete the caller's state machine twice.
            val a = if (raw.lastOrNull() is Continuation<*>) raw.dropLast(1) else raw

            return when (method.name) {
                "updateSessionTitleAndCategoryWithModelSnapshot" -> {
                    modelSnapshotWrites += TitleRow(
                        sessionId = a[0] as String,
                        title = a[1] as String,
                        category = a[2] as String?,
                        entryId = a[3] as String?,
                        modelId = a[4] as String?,
                        displayName = a[5] as String?,
                        providerType = a[6] as String?,
                        generatedAt = a[7] as Long?,
                    )
                    Unit
                }
                "updateSessionTitleAndCategory" -> {
                    plainWrites += TitleRow(
                        sessionId = a[0] as String,
                        title = a[1] as String,
                        category = a[2] as String?,
                        entryId = null,
                        modelId = null,
                        displayName = null,
                        providerType = null,
                        generatedAt = null,
                    )
                    Unit
                }
                "toString" -> "RecordingChatDao(${modelSnapshotWrites.size} attributed, ${plainWrites.size} plain)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a.firstOrNull()
                else -> throw UnsupportedOperationException(
                    "RecordingChatDao: unexpected ${method.name}(...). The title write path " +
                        "reached for a DAO method this test does not model — model it " +
                        "explicitly instead of letting it pass silently.",
                )
            }
        }
    }

    /**
     * The span of a top-level function, from its declaration to the next sibling.
     *
     * Scoping matters: `activeEntryId = _activeEntryId.value` and
     * `updateSessionTitleAndCategoryWithModelSnapshot` each occur in several
     * unrelated functions, so a file-wide `contains` would stay green even after
     * the code under test was deleted.
     */
    private fun functionBody(source: String, anchor: String): String? {
        val start = source.indexOf(anchor)
        if (start < 0) return null
        val rest = source.substring(start + anchor.length)
        val next = Regex("\n    private (suspend )?fun ").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    /**
     * [body] with `//` comments removed — presence checks must run on code.
     *
     * The comment explaining WHY the attribution was removed naturally names the
     * removed field, so a raw `contains` turns red on documentation. `//` inside
     * a string literal (a URL, say) is kept, decided by quote parity before it.
     */
    private fun code(body: String): String = body.lines().joinToString("\n") { line ->
        val marker = line.indexOf("//")
        if (marker >= 0 && line.take(marker).count { it == '"' } % 2 == 0) line.take(marker) else line
    }

    private fun sessionListViewModelSource(): String {
        val candidates = listOf(
            File("src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt"),
            File("app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }?.readText()
            ?: error("SessionListViewModel.kt not found from ${File(".").absolutePath}")
    }

    private fun chatViewModelSource(): String {
        val candidates = listOf(
            File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }?.readText()
            ?: error("ChatViewModel.kt not found from ${File(".").absolutePath}")
    }
}
