package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.shared.KotlinSourceText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-browser-restore-tab-page-id] The model-visible schema of `browser_use` must
 * advertise every argument the parser actually reads.
 *
 * ## Why this file exists
 *
 * `restore_tab` was reachable but unusable: `BrowserAction.RESTORE_TAB` is part of
 * the `action` enum the model is shown, and `BrowserActionInput` reads a `page_id`
 * argument, but `page_id` appeared nowhere in the tool schema — not in
 * `properties`, not in `propertyOrdering`. A model asked to bring back a slept page
 * could see the action and could see the id in `list_tabs` output
 * (`Sleeping page <id>: …`), but had no field name to put it in. Calling the action
 * anyway returned `restore_tab requires page_id`.
 *
 * The schema is just a string the model reads, so no runtime input can expose a
 * mismatch like this: it fails only in front of a user, as a tool "that does not
 * work". So the invariant is pinned here instead — see
 * [every argument the parser reads is advertised in the schema].
 *
 * The key list is extracted from the parser source because the keys exist **only as
 * string literals** (there is no reflective way to ask `BrowserActionInput` what it
 * reads). `KotlinSourceText.noComments` is the right mask for exactly that question —
 * it keeps literals and drops comments, so a commented-out `"page_id"` cannot pass
 * for a real read. The schema side is read from the **real** tool definition, not
 * from the source text, so a renamed or restructured schema is still checked.
 */
class BrowserUseToolSchemaTest {

    private fun mainRoot(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("main source root not found; run from the module or repo root")

    private fun readProduction(relative: String): String {
        val file = File(mainRoot(), relative)
        assertTrue("missing production source: ${file.path}", file.isFile)
        return file.readText()
    }

    /** The schema the model actually receives. */
    private fun inputSchema(): JSONObject =
        BrowserUseTool.toolDefinition().getJSONObject("input_schema")

    private fun advertisedProperties(): Set<String> =
        inputSchema().getJSONObject("properties").keys().asSequence().toSet()

    private fun propertyOrdering(): List<String> =
        inputSchema().getJSONArray("propertyOrdering").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }

    /**
     * Argument names `BrowserActionInput` reads off the incoming JSON object.
     *
     * The function list is explicit rather than a loose `opt...` prefix so that a
     * future reader type (say `optJSONObject`) cannot be silently missed: the probe
     * below asserts the extraction still sees the known set.
     */
    private fun parsedArgumentKeys(): Set<String> {
        val src = KotlinSourceText.noComments(
            readProduction("com/openminis/app/browser/BrowserActionInput.kt"),
        )
        val regex = Regex(
            """obj\.(?:optString|optInt|optBoolean|optDouble|optLong|optJSONArray|optJSONObject|has)\("([a-z_]+)"""",
        )
        return regex.findAll(src).map { it.groupValues[1] }.toSet()
    }

    /**
     * Keys the parser reads that are deliberately absent from the schema because
     * they are **aliases** of an advertised key, with a fallback chain that still
     * works when the model uses the advertised name.
     *
     * Each entry must stay justified: the assertion below fails if an exempted key
     * is not read at all any more (a stale exemption), and the alias chains
     * themselves are pinned by [aliases the schema omits still fall back to the advertised key].
     */
    private val aliasKeysOmittedFromSchema = setOf("bookmark_id", "history_id")

    // ── the defect that prompted this file ───────────────────────────────

    @Test
    fun `restore_tab is reachable and its page_id is advertised`() {
        assertTrue(
            "restore_tab must stay part of the model-visible action enum",
            BrowserAction.allValues.contains(BrowserAction.RESTORE_TAB.value),
        )
        assertTrue(
            "the schema must advertise page_id, otherwise the model has no field to " +
                "put the id it was shown into; advertised=${advertisedProperties().sorted()}",
            advertisedProperties().contains("page_id"),
        )
        assertTrue(
            "page_id must also appear in propertyOrdering, which is what the clients " +
                "send to the model as the field order",
            propertyOrdering().contains("page_id"),
        )
    }

    @Test
    fun `a page_id supplied by the model reaches the parser`() {
        val parsed = BrowserActionInput.parse(
            """{"tool_title":"restore","action":"restore_tab","page_id":"abcdef01-2345"}""",
        )
        assertTrue("parse() rejected a well-formed restore_tab call", parsed != null)
        assertEquals("abcdef01-2345", parsed!!.pageId)
        assertTrue(
            "restore_tab requires page_id, so a call shaped exactly like the schema " +
                "says must not be missing it",
            !parsed.pageId.isNullOrBlank(),
        )
    }

    // ── the invariant that keeps the next one from shipping ──────────────

    @Test
    fun `every argument the parser reads is advertised in the schema`() {
        val advertised = advertisedProperties()
        val read = parsedArgumentKeys()

        // Probe: the extraction still works and still sees a known key. Without this,
        // a broken mask or regex would silently produce an empty set and the
        // difference below would trivially pass.
        assertTrue(
            "extraction probe failed — 'action' must be found among the parsed keys, " +
                "found=${read.sorted()}",
            read.contains("action"),
        )

        val missing = (read - advertised - aliasKeysOmittedFromSchema).sorted()
        assertEquals(
            "these arguments are read by BrowserActionInput but are NOT in the " +
                "model-visible schema, so no model can ever supply them: $missing\n" +
                "Either advertise them in BrowserUseTool.properties (+ propertyOrdering), " +
                "or — if they are aliases of an advertised key with a working fallback — " +
                "add them to aliasKeysOmittedFromSchema with the reason.",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `aliases the schema omits still fall back to the advertised key`() {
        // A stale exemption would otherwise let a genuinely unreachable argument hide
        // behind it forever.
        val read = parsedArgumentKeys()
        for (alias in aliasKeysOmittedFromSchema) {
            assertTrue(
                "exemption '$alias' is stale: BrowserActionInput no longer reads it",
                read.contains(alias),
            )
            assertTrue(
                "exemption '$alias' is only justified if the advertised key it aliases " +
                    "is itself advertised",
                advertisedProperties().contains("item_id"),
            )
        }
        // And the alias chain must really resolve when the model uses the advertised
        // name — otherwise dropping the alias from the schema would be a real loss.
        val viaItemId = BrowserActionInput.parse(
            """{"tool_title":"remove","action":"remove_bookmark","item_id":"bm-1"}""",
        )
        assertTrue("parse() rejected a well-formed remove_bookmark call", viaItemId != null)
        assertEquals("bm-1", viaItemId!!.itemId)
    }
}
