package com.openminis.app.data.model

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orphan-key regression net for `ProviderConfig.subAgentModelNotes`.
 *
 * ## Why this is split in two
 *
 * `ProviderRepository`'s only constructor reads EncryptedSharedPreferences and
 * opens Room, so its deletion methods cannot be executed by any JVM unit test in
 * this project (the same wall documented in `SingleJudgementWiringTest`). The
 * net is therefore two layers, and they are NOT interchangeable:
 *
 *   - [SubAgentNoteCascadeBehaviourTest] is a real BEHAVIOUR test. It calls the
 *     production cascade function and the production serializer.
 *   - [SubAgentNoteCascadeWiringTest] is a TEXT assertion. It reads the
 *     production source and asserts each deletion path actually invokes the
 *     cascade. A failure there means "look at the wiring", never "the behaviour
 *     broke" — do not describe it as proving behaviour.
 */
class SubAgentNoteCascadeBehaviourTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `dropping a note removes exactly that key and nothing else`() {
        val config = ProviderConfig(
            subAgentModelNotes = mutableMapOf(
                "entry-a" to "fast drafting",
                "entry-b" to "long-context review",
                "group-c" to "whole-group guidance",
            ),
        )

        config.dropSubAgentModelNote("entry-a")

        assertEquals(
            setOf("entry-b", "group-c"),
            config.subAgentModelNotes.keys,
        )
        assertEquals("long-context review", config.subAgentModelNotes["entry-b"])
    }

    @Test
    fun `dropping an id that was never noted is a no-op`() {
        val config = ProviderConfig(
            subAgentModelNotes = mutableMapOf("entry-a" to "keep me"),
        )

        config.dropSubAgentModelNote("never-noted")

        assertEquals(mapOf("entry-a" to "keep me"), config.subAgentModelNotes)
    }

    /**
     * The point of the whole fix: the orphan used to ride along in the
     * `provider_config / config` prefs mirror, which is the WHOLE config
     * serialized on every save. This pins the exit, not just the map.
     */
    @Test
    fun `a dropped note no longer survives the config serialization`() {
        val config = ProviderConfig(
            subAgentModelNotes = mutableMapOf(
                "entry-a" to "orphan-in-waiting",
                "entry-b" to "deleted-but-noted",
            ),
        )
        val before = ProviderConfig.serializer().let { json.encodeToString(it, config) }
        assertTrue("precondition: the key is in the mirror to begin with", before.contains("orphan-in-waiting"))

        config.dropSubAgentModelNote("entry-a")

        val after = ProviderConfig.serializer().let { json.encodeToString(it, config) }
        assertFalse(
            "the deleted entry's note must not be re-serialized forever, got: $after",
            after.contains("orphan-in-waiting"),
        )
        assertTrue("the surviving entry's note must still be persisted", after.contains("deleted-but-noted"))
    }
}

/**
 * TEXT assertions (source-reading), in the style of
 * [com.openminis.app.data.SingleJudgementWiringTest].
 *
 * The audit listed three deletion paths that leave `subAgentModelNotes` keys
 * behind. Reading the file turned up a fourth: `replaceEntries` prunes entries
 * the upstream model list no longer returns, and it already cascades the group
 * member refs and the agent-loop pins in that branch. These tripwires keep all
 * of them wired to the one cascade function.
 */
class SubAgentNoteCascadeWiringTest {

    private fun source(relativePath: String): String =
        sequenceOf(
            File("app/src/main/java/com/openminis/app/$relativePath"),
            File("src/main/java/com/openminis/app/$relativePath"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relativePath from ${File(".").absolutePath}")

    @Test
    fun `every path that deletes an entry, group or instance cascades the note`() {
        val repo = source("data/repository/ProviderRepository.kt")

        assertTrue(
            "removeEntry must drop the note keyed by the removed entry id",
            repo.contains("config.dropSubAgentModelNote(entryId)"),
        )
        assertTrue(
            "removeGroup must drop the note keyed by the removed group id",
            repo.contains("config.dropSubAgentModelNote(groupId)"),
        )
        assertTrue(
            "removeInstance must drop the notes of every entry it deleted with the instance",
            repo.contains("removedEntryIds.forEach { config.dropSubAgentModelNote(it) }"),
        )
        assertTrue(
            "removeInstance also deletes the groups it empties — their notes orphan the same way",
            repo.contains("emptyGroupIds.forEach { config.dropSubAgentModelNote(it) }"),
        )
        assertTrue(
            "replaceEntries' prune branch must drop the notes of the entries the refresh dropped",
            repo.contains("prunedEntryIds.forEach { config.dropSubAgentModelNote(it) }"),
        )
    }

    @Test
    fun `the note cascade has exactly one implementation`() {
        val repo = source("data/repository/ProviderRepository.kt")
        val model = source("data/model/ProviderConfig.kt")

        assertTrue(
            "the cascade must live on the Context-free model so it can be executed by a unit test",
            model.contains("internal fun dropSubAgentModelNote(id: String)"),
        )
        // `subAgentModelNotes.remove(id)` inside setSubAgentModelNote is the
        // legitimate "user cleared the note" write and must stay. What must not
        // come back is a second, inline cascade in a deletion path.
        for (forbidden in listOf("subAgentModelNotes.remove(entryId)", "subAgentModelNotes.remove(groupId)")) {
            assertFalse(
                "an inline `$forbidden` copy came back in the repository — the copies are exactly what " +
                    "drift apart; deletion paths must call dropSubAgentModelNote",
                repo.contains(forbidden),
            )
        }
    }
}
