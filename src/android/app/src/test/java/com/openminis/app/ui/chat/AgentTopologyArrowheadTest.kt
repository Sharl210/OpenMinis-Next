package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-arrow-parity] The PNG exporter and the on-screen canvas must
 * draw the SAME edge direction.
 *
 * Found by an independent audit: the exporter drew an arrowhead and the screen
 * renderer drew only the polyline, so on a device A→B and B→A were visually
 * identical while the exported PNG distinguished them. Both now consume
 * [topologyArrowheadSegments]; these tests pin the geometry, because "both call the
 * same function" is not by itself observable.
 *
 * NOT a requirement: request.md has no clause demanding arrowheads (the "箭头" hits
 * near :97/:99 are about the four jump buttons, a different feature). This is an
 * internal-consistency defect, and it is recorded as such rather than dressed up as
 * a requirement miss.
 */
class AgentTopologyArrowheadTest {

    private fun seg(angleDeg: Double, length: Float = 10f, halfWidth: Float = 5f): List<Pair<AgentTopologyPoint, AgentTopologyPoint>> {
        val rad = Math.toRadians(angleDeg)
        val tip = AgentTopologyPoint(100f, 100f)
        val before = AgentTopologyPoint(
            tip.x - (50f * Math.cos(rad)).toFloat(),
            tip.y - (50f * Math.sin(rad)).toFloat(),
        )
        return topologyArrowheadSegments(listOf(before, tip), length, halfWidth)
    }

    @Test
    fun `a two-point edge produces two barbs at the tip`() {
        val barbs = topologyArrowheadSegments(
            listOf(AgentTopologyPoint(0f, 0f), AgentTopologyPoint(50f, 0f)),
        )
        assertEquals(2, barbs.size)
        barbs.forEach { (tip, _) ->
            assertEquals("every barb starts at the tip (x)", 50f, tip.x, 0.001f)
            // The edge runs along y=0, so the tip's y is 0 — my first draft repeated
            // the x assertion here and asserted 50f, which no implementation could
            // satisfy.
            assertEquals("the tip's y follows the edge", 0f, tip.y, 0.001f)
        }
    }

    @Test
    fun `an edge with fewer than two points has no direction to show`() {
        // A single-point (or empty) edge cannot express a direction; returning
        // nothing keeps the renderers from drawing a degenerate barb.
        assertTrue(topologyArrowheadSegments(emptyList()).isEmpty())
        assertTrue(topologyArrowheadSegments(listOf(AgentTopologyPoint(1f, 2f))).isEmpty())
    }

    @Test
    fun `barbs sit behind the tip, so the arrow points along the edge`() {
        // The barbs must trail the tip, never stick out in front of it — otherwise
        // the arrowhead would read as pointing backwards.
        val barbs = seg(0.0)
        barbs.forEach { (tip, barb) ->
            assertTrue("barb must be behind the tip (x), was ${barb.x} vs ${tip.x}", barb.x < tip.x)
        }
    }

    @Test
    fun `the two barbs are mirror images about the edge axis`() {
        // Symmetry is what makes it look like an arrowhead rather than a flag.
        val (a, b) = seg(0.0)
        assertEquals("equal distance behind the tip", a.second.x, b.second.x, 0.001f)
        assertEquals("equal spread, opposite signs", a.second.y - 100f, -(b.second.y - 100f), 0.001f)
    }

    @Test
    fun `rotating the edge rotates the barbs by the same angle`() {
        // The exporter used to get this from a canvas rotation; doing the maths
        // explicitly is only equivalent if the rotation is actually applied.
        val right = seg(0.0)
        val down = seg(90.0)
        // Pointing right ⇒ barbs trail to the left of the tip.
        right.forEach { assertTrue(it.second.x < 100f) }
        // Pointing down ⇒ barbs trail upward of the tip.
        down.forEach { assertTrue(it.second.y < 100f) }
        // Same distance in both cases: a pure rotation preserves length.
        val rightLen = kotlin.math.hypot((right[0].second.x - 100f).toDouble(), (right[0].second.y - 100f).toDouble())
        val downLen = kotlin.math.hypot((down[0].second.x - 100f).toDouble(), (down[0].second.y - 100f).toDouble())
        assertEquals(rightLen, downLen, 0.01)
    }

    @Test
    fun `the geometry is scale-aware rather than hardcoded`() {
        // The parameters exist so the arrow can be tuned; a hardcoded 10f/5f inside
        // the function would silently ignore them.
        val small = seg(0.0, length = 4f, halfWidth = 2f)
        val large = seg(0.0, length = 20f, halfWidth = 10f)
        val smallReach = 100f - small[0].second.x
        val largeReach = 100f - large[0].second.x
        assertTrue("a longer barb must reach further back", largeReach > smallReach)
    }

    @Test
    fun `a diagonal edge still trails the tip rather than only handling axes`() {
        // Guards against a fix that special-cases horizontal/vertical edges.
        val barbs = seg(45.0)
        barbs.forEach { (tip, barb) ->
            val dot = (tip.x - barb.x) * kotlin.math.cos(Math.toRadians(45.0)).toFloat() +
                (tip.y - barb.y) * kotlin.math.sin(Math.toRadians(45.0)).toFloat()
            assertTrue("the barb must lie behind the tip along the edge direction", dot > 0f)
        }
    }

    @Test
    fun `a zero-length final segment does not produce NaN coordinates`() {
        // Degenerate input: the router can hand back two identical points. `atan2(0, 0)`
        // is defined (0.0) rather than NaN, but a future change to the maths could
        // reintroduce NaN silently — a NaN coordinate corrupts the drawn path without
        // being visibly wrong, so it is asserted rather than assumed.
        val duplicated = listOf(AgentTopologyPoint(5f, 5f), AgentTopologyPoint(5f, 5f))
        assertTrue("a degenerate edge should still yield drawable barbs", topologyArrowheadSegments(duplicated).isNotEmpty())
        topologyArrowheadSegments(duplicated).forEach { (tip, barb) ->
            listOf(tip.x, tip.y, barb.x, barb.y).forEach {
                assertFalse("no coordinate may be NaN", it.isNaN())
                assertFalse("no coordinate may be infinite", it.isInfinite())
            }
        }
    }
}
