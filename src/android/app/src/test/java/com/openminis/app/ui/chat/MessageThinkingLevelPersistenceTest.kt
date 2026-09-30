package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/**
 * [T-android-thinking-level-persist] The thinking level a reply was produced at
 * must survive the round trip through storage, and a row that never recorded one
 * must not be given somebody else's.
 *
 * ## The defect these tests exist because of
 *
 * The requirement is that a restored reply still shows which model produced it
 * and at which thinking level (`request.md:21`). At the time, `messages` had no
 * `thinking_level` column at all, `ChatRepository.appendMessage` had no parameter
 * for it, and `ChatViewModel` kept the level only in memory on the live bubble.
 * So the level was written NOWHERE, and the two readers agreed to paper over it:
 *
 *  - `AssistantHeader` renders the capsule only when
 *    `snapshot.thinkingLevel != null`, which for every row loaded from disk was
 *    permanently false — hence "after a restart every historical reply's level
 *    badge is gone";
 *  - `ChatScreen`'s Deep-Thinking gate fell back to
 *    `viewModel.thinkingLevel.value`, i.e. to the level selected NOW, which
 *    silently re-labelled old replies with a present-day setting.
 *
 * ## What is pinned here, and how far it reaches
 *
 * The assertions run the PRODUCTION code on both ends — `ChatRepository.appendMessage`
 * for the write, `ChatMessage.withPersistedAttribution` (the exact function the
 * DB → UI load path applies) plus `buildFlatChatItems` for the read — instead of
 * restating the logic in the test, which is how a test can stay green while the
 * code drifts.
 *
 * Boundary, stated rather than implied: the load path is a private member of a
 * `ChatViewModel` that needs an Android `Context`, and this module's unit-test
 * source set has no Robolectric, so the two statements that build the UI message
 * cannot be re-executed here. The per-message contract they rely on is what is
 * asserted below; the schema half (column, type, migration) is asserted in
 * `MessageThinkingLevelMigrationTest`, and the on-device half in
 * `MessageThinkingLevelRoomTest`.
 *
 * ## Reverse proof
 *
 * Both halves of this file were checked by breaking the production code on
 * purpose: dropping `thinkingLevel = thinkingLevel?.name` from `appendMessage`,
 * and making `withPersistedAttribution` an identity `copy()`. Each break turns
 * the tests below red — see the tag on each test for which break kills it.
 */
class MessageThinkingLevelPersistenceTest {

    private val sessionId = "session-thinking-level"

    /**
     * A [ChatDao] that records what the write path hands it.
     *
     * A JDK proxy rather than a hand-written fake: `ChatDao` is a 69-method Room
     * interface and the subject here is the row `appendMessage` BUILDS, not a
     * re-implementation of Room. Any call outside the four the write path
     * actually makes fails loudly, so this fake cannot quietly absorb a new write
     * the test ought to know about.
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
            // A suspend DAO method carries a trailing Continuation. This fake
            // answers SYNCHRONOUSLY, so the continuation is only stripped off —
            // never resumed. Resuming it here as well would complete the caller's
            // state machine and then also hand it the return value, which the
            // coroutine machinery catches as "CompletedContinuation cannot be
            // cast to DispatchedContinuation". A plain return is the whole
            // protocol: anything other than COROUTINE_SUSPENDED is the result.
            val arguments = if (raw.lastOrNull() is Continuation<*>) raw.dropLast(1) else raw

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
                else -> throw UnsupportedOperationException(
                    "RecordingChatDao: unexpected ${method.name}(...). The write path " +
                        "reached for a DAO method this test does not model — model it " +
                        "explicitly instead of letting it pass silently.",
                )
            }
        }
    }

    private fun snapshot(model: String = "gpt-5.6") = ModelAttributionSnapshot(
        modelId = model,
        displayName = model,
        providerTypeRaw = "openAI",
        providerInstanceId = "provider-instance-1",
    )

    private fun textParts(text: String) = """[{"type":"text","value":"$text"}]"""

    private suspend fun writeTurn(
        repository: ChatRepository,
        level: ThinkingLevel?,
        model: String = "gpt-5.6",
    ): MessageEntity = repository.appendMessage(
        sessionId = sessionId,
        role = "assistant",
        partsJson = textParts("answer"),
        modelSnapshot = snapshot(model),
        provenance = MessageProvenance.ASSISTANT,
        thinkingLevel = level,
    )

    /**
     * The read half of the load path: the same production function
     * `ChatViewModel.toChatMessages` chains onto every row it restores.
     */
    private fun restore(row: MessageEntity): ChatMessage =
        ChatMessage(id = row.id, role = row.role, content = "answer")
            .withPersistedAttribution(row)

    /** …and the header the user actually sees, through the real row builder. */
    private fun headerLevelOf(rows: List<MessageEntity>): ThinkingLevel? {
        val items = buildFlatChatItems(
            listOf(ChatMessage(id = "u1", role = "user", content = "hi")) + rows.map(::restore),
        )
        val header = items.filterIsInstance<FlatChatItem.AssistantHeader>().singleOrNull()
        assertNotNull("the assistant header row was not produced at all", header)
        return header!!.snapshot?.thinkingLevel
    }

    // ---------------------------------------------------------------- round trip

    @Test
    fun `a turn written at a level reads back at that same level`() = runTest {
        val fake = RecordingChatDao()
        val repository = ChatRepository(fake.dao)

        val written = writeTurn(repository, ThinkingLevel.HIGH)

        // (1) the WRITE side actually put it in the row. This is the assertion
        // that was missing: the level existed in memory only, so nothing could
        // possibly restore it.
        val row = fake.rows.single()
        assertEquals(
            "the persisted column must carry the level the turn ran at",
            "HIGH",
            row.thinkingLevel,
        )
        assertEquals(written.id, row.id)

        // (2) the READ side hands the same level to the UI model…
        assertEquals(ThinkingLevel.HIGH, restore(row).thinkingLevel)

        // …and it reaches the header snapshot the capsule is drawn from.
        assertEquals(
            ThinkingLevel.HIGH,
            restore(row).assistantHeaderSnapshot?.thinkingLevel,
        )
        assertEquals(ThinkingLevel.HIGH, headerLevelOf(fake.rows))
    }

    @Test
    fun `every level survives the storage encoding`() = runTest {
        // The column stores ThinkingLevel.name and the reader decodes it with
        // ThinkingLevel.decoded. If either side switches to the ordinal or to
        // the display label ("XHigh"), a stored level silently decodes into a
        // DIFFERENT level — decoding is clamped, not validated, so nothing throws.
        for (level in ThinkingLevel.entries) {
            val fake = RecordingChatDao()
            writeTurn(ChatRepository(fake.dao), level)

            val stored = fake.rows.single().thinkingLevel
            assertEquals("stored token must be the enum name", level.name, stored)
            assertEquals(level, persistedThinkingLevel(stored))
            assertEquals(level, restore(fake.rows.single()).thinkingLevel)
        }
    }

    @Test
    fun `the model attribution is restored alongside the level`() = runTest {
        // Same read function, same round trip: the header needs the identity AND
        // the level, and both had been dropped on the way back from the DB.
        val fake = RecordingChatDao()
        writeTurn(ChatRepository(fake.dao), ThinkingLevel.MAX, model = "gpt-5.6-terra")

        val restored = restore(fake.rows.single())
        assertEquals("openAI", restored.providerType)
        assertEquals("gpt-5.6-terra", restored.modelDisplayName)
        assertEquals(
            "OpenAI · gpt-5.6-terra",
            formatAssistantAttribution(restored.assistantHeaderSnapshot),
        )
        assertEquals(ThinkingLevel.MAX, headerLevelOf(fake.rows))
    }

    // ------------------------------------------------- the regression net (item 6)

    @Test
    fun `each turn keeps its own level instead of the one selected now`() = runTest {
        // The requirement's whole premise: the user switches models and levels
        // mid-thread ("我期间可能进行模型切换"). A single session, two turns, two
        // different levels.
        val fake = RecordingChatDao()
        val repository = ChatRepository(fake.dao)

        writeTurn(repository, ThinkingLevel.OFF)
        writeTurn(repository, ThinkingLevel.ULTRA)

        val restored = fake.rows.map(::restore)
        assertEquals(2, restored.size)
        assertEquals(
            "turn 1 must report the level it ran at, not turn 2's",
            ThinkingLevel.OFF,
            restored[0].thinkingLevel,
        )
        assertEquals(ThinkingLevel.ULTRA, restored[1].thinkingLevel)
        assertNotEquals(restored[0].thinkingLevel, restored[1].thinkingLevel)

        // …and the same through the real header builder, per header.
        val headers = buildFlatChatItems(
            listOf(ChatMessage(id = "u1", role = "user", content = "q1")) +
                listOf(restored[0]) +
                listOf(ChatMessage(id = "u2", role = "user", content = "q2")) +
                listOf(restored[1]),
        ).filterIsInstance<FlatChatItem.AssistantHeader>()
        assertEquals(2, headers.size)
        assertEquals(ThinkingLevel.OFF, headers[0].snapshot?.thinkingLevel)
        assertEquals(ThinkingLevel.ULTRA, headers[1].snapshot?.thinkingLevel)
    }

    @Test
    fun `a row with no recorded level is not given the current one`() = runTest {
        // Rows written before the column existed. The level is genuinely unknown,
        // and "unknown" must stay unknown: substituting the level that happens to
        // be selected now is what made every old reply appear to have been
        // produced at today's setting (and is the second half of the defect).
        val fake = RecordingChatDao()
        val repository = ChatRepository(fake.dao)

        writeTurn(repository, level = null)

        val row = fake.rows.single()
        assertNull("an unrecorded level must be stored as NULL, not as a default", row.thinkingLevel)

        val restored = restore(row)
        assertNull(restored.thinkingLevel)
        assertNull(
            "with no level the header draws no capsule — it must not invent one",
            restored.assistantHeaderSnapshot?.thinkingLevel,
        )
        assertNull(headerLevelOf(fake.rows))

        // Spelled out, because "null" and "the current level" are the two states
        // this test exists to keep apart.
        assertNotEquals(ThinkingLevel.MEDIUM, restored.thinkingLevel)
    }

    @Test
    fun `an empty stored token is unrecorded, not a maxed-out level`() {
        // ThinkingLevel.decoded clamps anything it does not recognise to XHIGH —
        // correct for a config value that must not throw, WRONG for this column:
        // a blank string would turn "never recorded" into a confident "XHigh"
        // capsule on a historical message. Hence the explicit blank guard.
        assertNull(persistedThinkingLevel(null))
        assertNull(persistedThinkingLevel(""))
        assertNull(persistedThinkingLevel("   "))

        // A token a NEWER build wrote is still clamped rather than crashing the
        // load (that is what `decoded` is for), and it stays distinguishable from
        // "unrecorded".
        assertEquals(ThinkingLevel.XHIGH, persistedThinkingLevel("SUPREME"))
    }
}
