package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-card-layout] Guards the node card the requirement fixes.
 *
 * The requirement enumerates the card's content AND its line order
 * (`request.md`):
 *
 * > 作为它的标题，它是几级子代理 … 主代理，就是第一层，然后第二层就是一级子代理 … 每
 * > 一层可能会有多个子代理，那么多个子代理就是比如说二级子代理-1，-2以此类推，然后第二
 * > 行就是它被创建的时间，第三行就是的全局序号: 3这种然后就是一个水平分割线，然后就是
 * > …这个节点所对应的对话内容…这里用来展示的可以展示3行
 * > 每一个节点卡片还要加上…几级子代理的名字…后面加上这样的一个符号 · 再加上238.4K/M/B
 *
 * Two things had gone wrong, neither of which any test could see:
 *
 *  1. the title was an implementation identifier ("Agent 1", a model id) rather
 *     than the tree position the requirement names;
 *  2. the creation time and global order lines were drawn ONLY by the PNG export —
 *     the on-screen canvas the user actually looks at showed title+token then the
 *     body, so two of the required lines were invisible.
 *
 * The card builders are pure, so the required content and order can be pinned
 * without a Canvas, a Bitmap or a Compose runtime.
 */
class AgentTopologyCardLayoutTest {

    /**
     * Explicit Chinese labels. Passing them in (rather than relying on the JVM's
     * default locale) means these assertions test the label SHAPE and are not
     * sensitive to whichever locale the test runner happens to use — and it is
     * what lets the production code stay free of hardcoded Chinese.
     */
    private val zh = TopologyCardStrings(
        mainAgent = "主代理",
        subAgentTemplate = "%1\$s级子代理",
        levelNumerals = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十"),
        createdKnown = "创建于 %1\$s",
        createdUnknown = "创建时间未知",
        order = "全局序号: %1\$d",
    )

    private fun node(
        id: String = "n",
        title: String = "主代理",
        parentId: String? = null,
        depth: Int = 0,
        createdAtMillis: Long = 0L,
        globalOrder: Int = 0,
    ) = AgentTopologyNode(
        id = id,
        title = title,
        parentId = parentId,
        depth = depth,
        createdAtMillis = createdAtMillis,
        globalOrder = globalOrder,
    )

    // --- the title is the tree position, not a runtime identifier ---

    @Test
    fun `the level label names the tree position the requirement describes`() {
        // "主代理，就是第一层，然后第二层就是一级子代理"
        assertEquals("主代理", topologyLevelLabel(depth = 0, siblingIndex = 1, strings = zh))
        assertEquals("一级子代理-1", topologyLevelLabel(depth = 1, siblingIndex = 1, strings = zh))
        assertEquals("二级子代理-1", topologyLevelLabel(depth = 2, siblingIndex = 1, strings = zh))
        assertEquals("三级子代理-1", topologyLevelLabel(depth = 3, siblingIndex = 1, strings = zh))
    }

    @Test
    fun `siblings are distinguished by the dash suffix the requirement shows`() {
        // "二级子代理-1，-2以此类推"
        assertEquals("二级子代理-1", topologyLevelLabel(depth = 2, siblingIndex = 1, strings = zh))
        assertEquals("二级子代理-2", topologyLevelLabel(depth = 2, siblingIndex = 2, strings = zh))
        assertEquals("二级子代理-3", topologyLevelLabel(depth = 2, siblingIndex = 3, strings = zh))
    }

    @Test
    fun `the root never takes a sibling suffix even if one is supplied`() {
        // There is exactly one main agent; "-1" on it would imply peers.
        assertEquals("主代理", topologyLevelLabel(depth = 0, siblingIndex = 1, strings = zh))
        assertEquals("主代理", topologyLevelLabel(depth = 0, siblingIndex = 4, strings = zh))
    }

    @Test
    fun `a lone child still carries its index so the label never churns`() {
        // Deliberate: the requirement's example is "-1，-2", so "-1" is the first
        // form. Suppressing the suffix for an only child would ALSO rename the
        // node the moment a sibling appeared, so the index is always shown.
        assertEquals("一级子代理-1", topologyLevelLabel(depth = 1, siblingIndex = 1, strings = zh))
        // And adding a sibling leaves the first node's label untouched.
        assertEquals("一级子代理-1", topologyLevelLabel(depth = 1, siblingIndex = 1, strings = zh))
        assertEquals("一级子代理-2", topologyLevelLabel(depth = 1, siblingIndex = 2, strings = zh))
    }

    @Test
    fun `deep levels fall back to digits instead of inventing numerals`() {
        // depth 11+ has no readable numeral; a made-up name would be worse than
        // the number.
        assertEquals("11级子代理-1", topologyLevelLabel(depth = 11, siblingIndex = 1, strings = zh))
        assertEquals("11级子代理-2", topologyLevelLabel(depth = 11, siblingIndex = 2, strings = zh))
    }

    // --- sibling numbering is stable ---

    @Test
    fun `sibling indexes follow creation order and not list order`() {
        // Derived from globalOrder, so inserting an unrelated node earlier in the
        // array cannot renumber existing siblings.
        val indexes = topologySiblingIndexes(
            listOf(
                node(id = "root", depth = 0, globalOrder = 0),
                node(id = "b", parentId = "root", depth = 1, globalOrder = 2),
                node(id = "a", parentId = "root", depth = 1, globalOrder = 1),
            ),
        )
        assertEquals(1, indexes["a"])
        assertEquals(2, indexes["b"])
        assertFalse("the root is not a sibling of anything", indexes.containsKey("root"))
    }

    @Test
    fun `siblings under different parents are numbered independently`() {
        val indexes = topologySiblingIndexes(
            listOf(
                node(id = "root", depth = 0),
                node(id = "p1", parentId = "root", depth = 1, globalOrder = 1),
                node(id = "p2", parentId = "root", depth = 1, globalOrder = 2),
                node(id = "c1", parentId = "p1", depth = 2, globalOrder = 3),
                node(id = "c2", parentId = "p2", depth = 2, globalOrder = 4),
            ),
        )
        // Each is the first child of ITS parent, so both read "-1" rather than
        // the second being renumbered to "-2" purely because of tree shape.
        assertEquals(1, indexes["c1"])
        assertEquals(1, indexes["c2"])
        assertEquals(2, indexes["p2"])
    }

    @Test
    fun `a node whose parent was filtered out still gets a defined index`() {
        // Partial trees are reachable (an included child with an excluded parent);
        // the label must stay defined instead of vanishing.
        val indexes = topologySiblingIndexes(
            listOf(
                node(id = "orphanA", parentId = "missing", depth = 2, globalOrder = 1),
                node(id = "orphanB", parentId = "missing", depth = 2, globalOrder = 2),
            ),
        )
        assertEquals(1, indexes["orphanA"])
        assertEquals(2, indexes["orphanB"])
    }

    // --- the card's required content and order ---

    @Test
    fun `the card carries exactly the three header lines the requirement lists`() {
        val card = topologyCardLines(
            node(createdAtMillis = 1_700_000_000_000L, globalOrder = 3),
            tokenLabel = "238.4K",
            strings = zh,
        )
        assertEquals("level · token, per `几级子代理的名字…· 238.4K`", "主代理 · 238.4K", card.title)
        assertTrue("line 2 must be the creation time", card.created.contains("创建于"))
        assertTrue("line 3 must be the global order", card.order.contains("3"))
        assertEquals(3, card.header.size)
    }

    @Test
    fun `header order is title then created then order`() {
        // The requirement numbers them explicitly; the renderers draw `header`
        // verbatim, so order here IS order on screen and in the export.
        val card = topologyCardLines(
            node(createdAtMillis = 1_700_000_000_000L, globalOrder = 7),
            tokenLabel = "1.2K",
            strings = zh,
        )
        assertEquals(card.title, card.header[0])
        assertEquals(card.created, card.header[1])
        assertEquals(card.order, card.header[2])
    }

    @Test
    fun `token count is joined to the level name with the middle dot`() {
        // "后面加上这样的一个符号 ·"
        val card = topologyCardLines(node(title = "二级子代理-2"), tokenLabel = "238.4K", strings = zh)
        assertEquals("二级子代理-2 · 238.4K", card.title)
        assertTrue(card.title.contains(" · "))
    }

    @Test
    fun `a node with no creation timestamp says so instead of printing zero`() {
        // 0L is the "unknown" sentinel from the runtime JSON, and rendering it as
        // a 1970 date would be a fabricated fact.
        val card = topologyCardLines(node(createdAtMillis = 0L), tokenLabel = "0", strings = zh)
        assertFalse("must not fabricate an epoch timestamp: ${card.created}", card.created.contains("01-01"))
        assertEquals("创建时间未知", card.created)
    }

    @Test
    fun `the global order line follows the requirement's numbered example`() {
        // "第三行就是的全局序号: 3"
        val card = topologyCardLines(node(globalOrder = 3), tokenLabel = "0", strings = zh)
        assertTrue("got '${card.order}'", card.order.endsWith("3"))
    }
}
