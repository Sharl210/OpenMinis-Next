package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.AgentTopologyExportQuality.Companion.fromStored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-export-quality] The export dialog used to only STATE the
 * default fineness; the requirement asks the user to choose it.
 *
 * Requirement (request.md:47, verbatim):
 *
 * > "然后点击以后，可以选择默认的一个那个叫做什么东西来着，就是那种什么 PPI 还是什么，
 * >  就是那选择那种什么像素的什么东西吧，就是保存的一个质量的那种感觉，就是精细度我
 * >  不知道那个叫什么来着，然后默认的话就是达到一个海报级别的质感的一个 PPI，然后导出
 * >  的话，可以选择带上背景或者纯透明通都用道 PNG 图片。"
 *
 * Two obligations hide in that sentence: (a) there IS a choice, and (b) the default
 * is poster grade. The old dialog satisfied only (b) — it rendered a description of
 * the default and no control at all, so "可以选择" had no UI behind it.
 *
 * These tests deliberately assert through [AgentTopologyPrefs.safeExportSize] rather
 * than only comparing enum constants: an enum whose tiers all resolve to the same
 * export size would satisfy a constants-only test while giving the user a selection
 * that changes nothing. (Exactly that counter-proof is recorded in plan.md.)
 *
 * NOT covered here, and not claimed: the SharedPreferences round-trip in
 * `loadExportQuality` / `saveExportQuality`. This module has no Robolectric, so the
 * JVM cannot construct a Context. Only the pure mapping is pinned.
 */
class AgentTopologyExportQualityTest {

    // ---- the default is poster grade --------------------------------------------

    @Test
    fun `the default is the poster tier`() {
        assertEquals(AgentTopologyExportQuality.POSTER, AgentTopologyExportQuality.DEFAULT)
    }

    @Test
    fun `the poster tier is print grade`() {
        // "默认的话就是达到一个海报级别的质感的一个 PPI" — 300 DPI is the usual
        // print threshold, so the default must sit at or above it, not below.
        assertTrue(
            "the default DPI must be print grade, was ${AgentTopologyExportQuality.POSTER.dpi}",
            AgentTopologyExportQuality.POSTER.dpi >= 300,
        )
    }

    @Test
    fun `the standard tier is lighter than the poster tier`() {
        val standard = AgentTopologyExportQuality.STANDARD
        val poster = AgentTopologyExportQuality.POSTER
        assertTrue("standard DPI must be below poster", standard.dpi < poster.dpi)
        assertTrue("standard pixel budget must be below poster", standard.maxPixels < poster.maxPixels)
        assertTrue(
            "standard dimension cap must be below poster",
            standard.maxDimensionPx < poster.maxDimensionPx,
        )
    }

    @Test
    fun `there is no tier above poster, because that would offer an OOM`() {
        // POSTER sits at DEFAULT_MAX_EXPORT_PIXELS, a crash-prevention ceiling for a
        // single ARGB_8888 allocation (64M px ≈ 256 MB). A "higher quality" tier would
        // have to exceed the budget that exists to stop the allocation failing.
        val poster = AgentTopologyExportQuality.POSTER
        assertEquals(AgentTopologyPrefs.DEFAULT_MAX_EXPORT_PIXELS, poster.maxPixels)
        assertEquals(AgentTopologyPrefs.DEFAULT_MAX_EXPORT_DIMENSION_PX, poster.maxDimensionPx)
        assertEquals(AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_DPI, poster.dpi)
        assertEquals(2, AgentTopologyExportQuality.entries.size)
    }

    // ---- the choice actually changes the output ---------------------------------

    @Test
    fun `choosing a tier changes the real exported pixel count`() {
        // This is the obligation "可以选择" actually needs: a selection that leaves
        // the output identical is not a choice.
        val content = 9_000 to 9_000
        val standard = AgentTopologyPrefs.safeExportSize(
            contentWidthPx = content.first,
            contentHeightPx = content.second,
            maxPixels = AgentTopologyExportQuality.STANDARD.maxPixels,
            maxDimensionPx = AgentTopologyExportQuality.STANDARD.maxDimensionPx,
            dpi = AgentTopologyExportQuality.STANDARD.dpi,
        )
        val poster = AgentTopologyPrefs.safeExportSize(
            contentWidthPx = content.first,
            contentHeightPx = content.second,
            maxPixels = AgentTopologyExportQuality.POSTER.maxPixels,
            maxDimensionPx = AgentTopologyExportQuality.POSTER.maxDimensionPx,
            dpi = AgentTopologyExportQuality.POSTER.dpi,
        )
        assertTrue(
            "standard must produce fewer pixels than poster " +
                "(${standard.width}x${standard.height} vs ${poster.width}x${poster.height})",
            standard.width.toLong() * standard.height < poster.width.toLong() * poster.height,
        )
        assertTrue("standard must carry its own DPI", standard.dpi < poster.dpi)
    }

    @Test
    fun `the poster tier keeps content at full size when it fits the budget`() {
        // The default must not silently downscale ordinary content, or "poster grade"
        // would be a label rather than a fact.
        val size = AgentTopologyPrefs.safeExportSize(
            contentWidthPx = 4_000,
            contentHeightPx = 3_000,
            maxPixels = AgentTopologyExportQuality.POSTER.maxPixels,
            maxDimensionPx = AgentTopologyExportQuality.POSTER.maxDimensionPx,
            dpi = AgentTopologyExportQuality.POSTER.dpi,
        )
        assertEquals(4_000, size.width)
        assertEquals(3_000, size.height)
        assertEquals(AgentTopologyExportQuality.POSTER.dpi, size.dpi)
    }

    @Test
    fun `every tier stays inside the safety budget it declares`() {
        (AgentTopologyExportQuality.entries).forEach { tier ->
            val size = AgentTopologyPrefs.safeExportSize(
                contentWidthPx = 200_000,
                contentHeightPx = 200_000,
                maxPixels = tier.maxPixels,
                maxDimensionPx = tier.maxDimensionPx,
                dpi = tier.dpi,
            )
            assertTrue("$tier exceeded its pixel budget", size.width.toLong() * size.height <= tier.maxPixels)
            assertTrue("$tier exceeded its dimension cap", size.width <= tier.maxDimensionPx)
            assertTrue("$tier exceeded its dimension cap", size.height <= tier.maxDimensionPx)
            assertEquals("$tier must keep its DPI as metadata", tier.dpi, size.dpi)
        }
    }

    // ---- stored-name tolerance --------------------------------------------------

    @Test
    fun `a stored tier name round-trips`() {
        AgentTopologyExportQuality.entries.forEach { tier ->
            assertEquals(tier, fromStored(tier.name))
        }
    }

    @Test
    fun `an unknown or absent stored value falls back to the default instead of crashing`() {
        // The pref is a plain string in SharedPreferences, so it can hold a name from
        // an older or newer build — or a hand-edited value. Opening the export dialog
        // must never crash on that.
        assertNotNull(fromStored(null))
        assertEquals(AgentTopologyExportQuality.DEFAULT, fromStored(null))
        assertEquals(AgentTopologyExportQuality.DEFAULT, fromStored(""))
        assertEquals(AgentTopologyExportQuality.DEFAULT, fromStored("ULTRA"))
        // Case matters: names are written by the app, so a case mismatch is corruption,
        // not a supported spelling.
        assertEquals(AgentTopologyExportQuality.DEFAULT, fromStored("poster"))
    }
}
