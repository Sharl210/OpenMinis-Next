package com.openminis.app.data

import com.openminis.app.data.repository.reorderById
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-provider-reorder] Pins the ordering semantics of
 * `ProviderRepository.reorderInstances` (mirrors iOS
 * `ProviderConfigStore.reorderInstances`).
 *
 * The repository itself needs a Context + Room + EncryptedSharedPreferences, so
 * this exercises the pure permutation rule the method implements. That rule is
 * the whole contract the UI depends on: ProviderListScreen drags within ONE
 * provider-type section and commits only that section's ids, relying on
 * "unmentioned instances keep their relative order" to leave the other sections
 * untouched. Getting that wrong is the Android shape of the iOS index-mapping
 * bug (246a8a8e), where section-local indices were mapped onto the global list
 * and rows landed in the wrong slot / snapped back.
 *
 * Persistence needs no separate assertion: ProviderConfigMapping writes
 * `sortOrder = idx` from the list index and the DAO reads
 * `ORDER BY sort_order ASC`, so list position IS the stored order.
 */
class ProviderReorderTest {

    /**
     * The ids are their own keys, so this adapter just supplies `idOf`. The rule
     * itself is [reorderById] — production, not a transcription of it: this used
     * to carry a copy of the algorithm, which meant a drift between the copy and
     * the real method was invisible by construction.
     */
    private fun reorder(current: List<String>, newOrder: List<String>): List<String> =
        reorderById(current, newOrder) { it }

    @Test
    fun `a full permutation is applied verbatim`() {
        assertEquals(
            listOf("c", "a", "b"),
            reorder(current = listOf("a", "b", "c"), newOrder = listOf("c", "a", "b")),
        )
    }

    @Test
    fun `reordering one section leaves other sections in place`() {
        // openAI = [o1, o2], anthropic = [a1, a2]. The user drags o2 above o1;
        // the screen commits ONLY the openAI ids.
        val current = listOf("o1", "o2", "a1", "a2")
        val result = reorder(current, newOrder = listOf("o2", "o1"))
        assertEquals(listOf("o2", "o1", "a1", "a2"), result)
    }

    @Test
    fun `an unmentioned trailing section keeps its internal order`() {
        val current = listOf("o1", "o2", "a1", "a2", "g1")
        val result = reorder(current, newOrder = listOf("o2", "o1"))
        // a1 before a2, g1 last — untouched.
        assertEquals(listOf("o2", "o1", "a1", "a2", "g1"), result)
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        // A stale drag referencing an instance deleted in another screen must
        // not resurrect it.
        val result = reorder(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a"))
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        val result = reorder(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a"))
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `an empty new order leaves everything untouched`() {
        val current = listOf("a", "b", "c")
        assertEquals(current, reorder(current, newOrder = emptyList()))
    }

    @Test
    fun `no instance is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = reorder(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(
            "the result must be a permutation of the input",
            current.sorted(), result.sorted(),
        )
        assertEquals(current.size, result.size)
    }

    /** A tiny carrier so the test can ask about *object* identity, not just ids. */
    private data class Item(val id: String, val label: String)

    @Test
    fun `the returned entries are the same objects, not rebuilt from their ids`() {
        val a = Item("a", "Alpha")
        val b = Item("b", "Beta")
        val out = reorderById(listOf(a, b), listOf("b")) { it.id }
        assertEquals(listOf("b", "a"), out.map { it.id })
        assertSame("a reordered entry must be the entry itself", b, out[0])
        assertSame("an unmentioned entry must be the entry itself", a, out[1])
    }

    @Test
    fun `the rule is a pure function - the caller's list is left alone`() {
        val current = listOf(Item("a", "Alpha"), Item("b", "Beta"))
        reorderById(current, listOf("b", "a")) { it.id }
        assertEquals("the input list must not be reordered in place", listOf("a", "b"), current.map { it.id })
    }
}
