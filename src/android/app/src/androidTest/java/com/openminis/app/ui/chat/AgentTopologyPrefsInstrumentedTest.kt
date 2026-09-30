package com.openminis.app.ui.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-android-topology-prefs-instrumented] Device-level proof for the agent topology
 * map's remembered settings.
 *
 * WHY THIS NEEDS A DEVICE: the JVM suite cannot construct a `Context`, so
 * [AgentTopologyPrefs] had no coverage at all — the zoom round-trip, the
 * single-global-value property and the export settings were all unverified. A test
 * over the pure helpers cannot stand in: those helpers would keep passing even if
 * `saveZoom` wrote to a key `loadZoom` never reads, or if the value were stored
 * per-session (which would look identical in-process and only differ across
 * sessions).
 *
 * Requirements covered, verbatim from request.md:
 *  - :65 "这个缩放的比例尺是可以记忆的…那么我们整一个应用都是通用记忆的…不管是哪一个
 *        对话中，或者哪一个子对话中缩放过，那么缩放这个比例系数是整个应用通用的"
 *  - :67 "里面滑动或者拖动过的位置它是不进行记忆的"
 *  - :47 the export fineness/background choice must be selectable and remembered
 *
 * The prefs file is shared application state, so every test restores the defaults
 * on the way out — a leaked non-default zoom would silently change what the next
 * test (or a real launch) sees.
 */
@RunWith(AndroidJUnit4::class)
class AgentTopologyPrefsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var prefs: AgentTopologyPrefs

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearPrefs()
        prefs = AgentTopologyPrefs(context)
    }

    @After
    fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    // ---- zoom is remembered (":65 缩放的比例尺是可以记忆的") ----------------------

    @Test
    fun zoomSurvivesANewPrefsInstance() {
        // A fresh instance re-reads from disk. If saveZoom/loadZoom disagreed about
        // the key, the value would be lost here while an in-memory field would have
        // hidden that.
        prefs.saveZoom(1.75f)
        val reopened = AgentTopologyPrefs(context)
        assertEquals(1.75f, reopened.loadZoom(), 0.0001f)
    }

    @Test
    fun zoomIsOneGlobalValueSharedByEverySession() {
        // ":65 …整一个应用都是通用记忆的" — the coefficient is app-wide, so a zoom
        // performed "inside" one session must be what another session sees. The prefs
        // object has no session parameter at all (that is the point), and two
        // independently constructed instances stand in for two sessions.
        val sessionA = AgentTopologyPrefs(context)
        sessionA.saveZoom(2.5f)
        val sessionB = AgentTopologyPrefs(context)
        assertEquals(
            "a zoom set in one session must be visible from another",
            2.5f,
            sessionB.loadZoom(),
            0.0001f,
        )
    }

    @Test
    fun anOutOfRangeStoredZoomIsClampedOnRead() {
        // The pref is a plain float a user (or a downgrade) can leave out of range.
        // Opening the map must clamp, never crash or zoom to 400x.
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("global_zoom", 99f)
            .commit()
        val loaded = AgentTopologyPrefs(context).loadZoom()
        assertTrue("zoom must clamp to the supported maximum", loaded <= AgentTopologyPrefs.MAX_GLOBAL_ZOOM)
        assertEquals(AgentTopologyPrefs.MAX_GLOBAL_ZOOM, loaded, 0.0001f)
    }

    @Test
    fun aNonFiniteStoredZoomFallsBackToTheDefaultInsteadOfPoisoningTheCanvas() {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("global_zoom", Float.NaN)
            .commit()
        assertEquals(
            AgentTopologyPrefs.DEFAULT_GLOBAL_ZOOM,
            AgentTopologyPrefs(context).loadZoom(),
            0.0001f,
        )
    }

    @Test
    fun theSavedZoomIsTheNormalizedValue() {
        // saveZoom returns what it persisted, so the caller can adopt the clamped
        // value instead of keeping a 99x in its own state.
        val persisted = prefs.saveZoom(99f)
        assertEquals(AgentTopologyPrefs.MAX_GLOBAL_ZOOM, persisted, 0.0001f)
        assertEquals(persisted, AgentTopologyPrefs(context).loadZoom(), 0.0001f)
    }

    @Test
    fun panAndCenterPositionAreDeliberatelyNotPersisted() {
        // ":67 里面滑动或者拖动过的位置它是不进行记忆的，因为…每一次进入，我们都会把
        // 当前的子代理设置为视觉中心". A stored position would fight the recentring.
        val storedKeys = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .all.keys
        val positional = storedKeys.filter { key ->
            val k = key.lowercase()
            k.contains("pan") || k.contains("offset") || k.contains("center") || k.contains("scroll")
        }
        assertTrue(
            "zoom is remembered but viewport position must not be: found $positional",
            positional.isEmpty(),
        )
    }

    // ---- export choices (":47 可以选择…带上背景或者纯透明") ----------------------

    @Test
    fun exportBackgroundChoiceRoundTrips() {
        prefs.saveExportTransparent(true)
        assertTrue(AgentTopologyPrefs(context).loadExportTransparent())
        prefs.saveExportTransparent(false)
        assertFalse(AgentTopologyPrefs(context).loadExportTransparent())
    }

    @Test
    fun exportBackgroundDefaultsToSolid() {
        assertEquals(
            AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_TRANSPARENT_BACKGROUND,
            AgentTopologyPrefs(context).loadExportTransparent(),
        )
        assertFalse("the documented default is a solid background", prefs.loadExportTransparent())
    }

    @Test
    fun exportQualityRoundTripsAndDefaultsToPoster() {
        assertEquals("poster grade is the documented default", AgentTopologyExportQuality.POSTER, prefs.loadExportQuality())
        prefs.saveExportQuality(AgentTopologyExportQuality.STANDARD)
        assertEquals(
            AgentTopologyExportQuality.STANDARD,
            AgentTopologyPrefs(context).loadExportQuality(),
        )
        prefs.saveExportQuality(AgentTopologyExportQuality.POSTER)
        assertEquals(
            AgentTopologyExportQuality.POSTER,
            AgentTopologyPrefs(context).loadExportQuality(),
        )
    }

    @Test
    fun anUnrecognisedStoredExportQualityFallsBackToTheDefault() {
        // The value is a plain string; it can hold a name from a newer build or a
        // hand-edited file. Opening the export dialog must not crash on it.
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("export_quality", "SOMETHING_ELSE")
            .commit()
        assertEquals(AgentTopologyExportQuality.DEFAULT, AgentTopologyPrefs(context).loadExportQuality())
    }

    @Test
    fun theTwoRememberedChoicesAreIndependent() {
        // A regression that routed both through one slot would still pass each of
        // the round-trip tests above; this is the test that would catch it.
        prefs.saveZoom(0.5f)
        prefs.saveExportTransparent(true)
        prefs.saveExportQuality(AgentTopologyExportQuality.STANDARD)

        val reloaded = AgentTopologyPrefs(context)
        assertEquals(0.5f, reloaded.loadZoom(), 0.0001f)
        assertTrue(reloaded.loadExportTransparent())
        assertEquals(AgentTopologyExportQuality.STANDARD, reloaded.loadExportQuality())

        // And changing one must not disturb the others.
        reloaded.saveZoom(2.0f)
        val again = AgentTopologyPrefs(context)
        assertTrue("zoom change wiped the background choice", again.loadExportTransparent())
        assertEquals(
            "zoom change wiped the quality choice",
            AgentTopologyExportQuality.STANDARD,
            again.loadExportQuality(),
        )
    }

    private companion object {
        /** Mirrors `AgentTopologyPrefs`'s private PREFS_NAME. */
        const val PREFS_NAME = "agent_topology_preferences"
    }
}
