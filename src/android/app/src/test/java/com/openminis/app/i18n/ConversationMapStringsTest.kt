package com.openminis.app.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMapStringsTest {
    private val requiredKeys = listOf(
        "chat_menu_conversation_map",
        "conversation_map_title",
        "conversation_map_close",
        "conversation_map_search_messages",
        "conversation_map_search",
        "conversation_map_no_messages",
        "conversation_map_no_matches",
        "conversation_map_jump_to_message",
        "conversation_map_empty_message",
        "conversation_map_expand_message",
        "conversation_map_collapse_message",
    )

    @Test
    fun `every locale declares all conversation map strings with matching format args`() {
        val working = File(System.getProperty("user.dir"))
        val resources = generateSequence(working) { it.parentFile }
            .take(8)
            .map { File(it, "src/main/res") }
            .firstOrNull { File(it, "values/strings.xml").isFile }
        assertTrue("could not locate app src/main/res from ${working.absolutePath}", resources != null)
        val localeDirs = resources!!.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").isFile }
            .sortedBy { it.name }
        assertTrue("no values resource directories found", localeDirs.isNotEmpty())

        for (localeDir in localeDirs) {
            val strings = File(localeDir, "strings.xml")
            assertTrue("missing ${localeDir.name}/strings.xml", strings.isFile)
            val xml = strings.readText()
            for (key in requiredKeys) {
                val declaration = Regex("<string\\b[^>]*\\bname=\\\"$key\\\"[^>]*>")
                assertTrue("${localeDir.name} is missing $key", declaration.containsMatchIn(xml))
            }
            val numbered = Regex("<string\\b[^>]*\\bname=\\\"conversation_map_jump_to_message\\\"[^>]*>([^<]*)</string>")
                .find(xml)?.groupValues?.get(1)
            assertTrue("${localeDir.name} must retain the %1\$d format argument", numbered?.contains("%1\$d") == true)
        }
    }
}
