package com.openminis.app.data

import com.openminis.app.data.repository.reorderById
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-modelgroup-reorder] Pins the ordering semantics of
 * `ProviderRepository.reorderModelGroups` (mirrors iOS
 * `ProviderConfigStore.reorderGroups`, 4ba54ff5).
 *
 * Same shape as ProviderReorderTest: the repository needs Context + Room, so
 * this exercises the pure permutation rule the method implements — which is
 * the whole contract the UI depends on. ModelGroupsScreen's drag commits the
 * full group-id list (like iOS's moveGroups), but the tolerant rules below
 * also protect against a stale drag racing a concurrent add/remove from
 * another screen or a sync.
 *
 * Persistence needs no separate assertion: ProviderConfigMapping writes each
 * group's `sort_order` from its list index at save time and the DAO reads
 * `ORDER BY sort_order ASC`, so list position IS the stored order.
 */
class ModelGroupReorderTest {

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
            listOf("coding", "daily", "translate"),
            reorder(
                current = listOf("daily", "coding", "translate"),
                newOrder = listOf("coding", "daily", "translate"),
            ),
        )
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        // A drag that raced a group deletion must not resurrect the group.
        val result = reorder(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a"))
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `a group added concurrently is appended, not lost`() {
        // Drag committed an id list captured before "new" was created.
        val result = reorder(current = listOf("a", "b", "new"), newOrder = listOf("b", "a"))
        assertEquals(listOf("b", "a", "new"), result)
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        assertEquals(
            listOf("b", "a", "c"),
            reorder(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a")),
        )
    }

    @Test
    fun `no group is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = reorder(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(current.sorted(), result.sorted())
        assertEquals(current.size, result.size)
    }

    private data class Group(val id: String, val name: String)

    @Test
    fun `grouping reorder keeps the group objects themselves`() {
        val g1 = Group("g1", "Fast")
        val g2 = Group("g2", "Slow")
        val out = reorderById(listOf(g1, g2), listOf("g2", "g1")) { it.id }
        assertEquals(listOf("g2", "g1"), out.map { it.id })
        assertSame(g2, out[0])
        assertSame(g1, out[1])
    }
}
