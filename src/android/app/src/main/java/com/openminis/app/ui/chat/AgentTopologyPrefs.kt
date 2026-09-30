package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Global preferences for the agent topology map.
 *
 * Only the global zoom is persisted. Pan/center coordinates intentionally stay
 * transient so every opening can recenter on the current node and no session
 * or device layout is coupled to a previous viewport.
 */
/**
 * [T-android-topology-export-quality] Selectable export fineness.
 *
 * The requirement asks for a CHOICE of quality with poster grade as the
 * default — "点击以后，可以选择默认的一个…就是保存的一个质量的那种感觉，就是精细度…
 * 默认的话就是达到一个海报级别的质感的一个 PPI" (request.md:47). The export
 * dialog previously only stated the default and offered no selection, so the
 * "choice" half of that sentence had no UI.
 *
 * Two dimensions move together, and they are honestly different things:
 *  - [maxDimensionPx]/[maxPixels] cap the REAL pixel count, which is what
 *    "fineness" means for a raster image;
 *  - [dpi] is the density written into the PNG, which fixes the physical
 *    print size for a given pixel count.
 *
 * There is deliberately no tier ABOVE [POSTER]. [DEFAULT_MAX_EXPORT_PIXELS]
 * is a crash-prevention ceiling for a single ARGB_8888 allocation (64M px ≈
 * 256 MB), not a product preference, so offering "higher than poster" would
 * be offering an OOM. See CAPACITY_LIMIT / the guard's own note: it is a
 * safety ceiling, and the poster tier already sits at it.
 */
enum class AgentTopologyExportQuality(val dpi: Int, val maxDimensionPx: Int, val maxPixels: Long) {
    /** Quick share: noticeably smaller file, still legible on screen. */
    STANDARD(dpi = 150, maxDimensionPx = 8_192, maxPixels = 16L * 1024L * 1024L),

    /** The default: print-grade density at the full pixel budget. */
    POSTER(
        dpi = AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_DPI,
        maxDimensionPx = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_DIMENSION_PX,
        maxPixels = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_PIXELS,
    ),
    ;

    companion object {
        val DEFAULT = POSTER

        fun fromStored(name: String?): AgentTopologyExportQuality =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * [T-android-topology-export-options] Whether the exported PNG omits its
 * background.
 *
 * The requirement offers the choice ("可以选择带上背景或者纯透明") and asks
 * for a remembered default, but the screen kept it in a `remember`, so it
 * silently reset to solid every time the canvas reopened. Persisting it
 * makes the choice stick, like the zoom this class already remembers.
 */
private const val KEY_EXPORT_TRANSPARENT = "export_transparent"

/** [T-android-topology-export-quality] See [loadExportQuality]. */
private const val KEY_EXPORT_QUALITY = "export_quality"

class AgentTopologyPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /** Returns the stored zoom, normalized to the supported global range. */
    fun loadZoom(): Float = normalizeZoom(
        runCatching { prefs.getFloat(KEY_GLOBAL_ZOOM, DEFAULT_GLOBAL_ZOOM) }
            .getOrDefault(DEFAULT_GLOBAL_ZOOM),
    )

    /** Stores only the normalized global zoom and returns the value persisted. */
    fun saveZoom(zoom: Float): Float {
        val normalized = normalizeZoom(zoom)
        prefs.edit().putFloat(KEY_GLOBAL_ZOOM, normalized).apply()
        return normalized
    }

    /** [T-android-topology-export-options] Remembered export background choice. */
    fun loadExportTransparent(): Boolean =
        prefs.getBoolean(KEY_EXPORT_TRANSPARENT, DEFAULT_POSTER_EXPORT_TRANSPARENT_BACKGROUND)

    fun saveExportTransparent(transparent: Boolean) {
        prefs.edit().putBoolean(KEY_EXPORT_TRANSPARENT, transparent).apply()
    }

    /**
     * [T-android-topology-export-quality] Remembered export fineness, like the
     * background choice above: the requirement is about what the user picks, and a
     * choice that resets on every reopen is not a choice.
     */
    fun loadExportQuality(): AgentTopologyExportQuality = AgentTopologyExportQuality.fromStored(
        runCatching { prefs.getString(KEY_EXPORT_QUALITY, null) }.getOrNull(),
    )

    fun saveExportQuality(quality: AgentTopologyExportQuality): AgentTopologyExportQuality {
        prefs.edit().putString(KEY_EXPORT_QUALITY, quality.name).apply()
        return quality
    }

    companion object {
        private const val PREFS_NAME = "agent_topology_preferences"
        private const val KEY_GLOBAL_ZOOM = "global_zoom"

        const val DEFAULT_GLOBAL_ZOOM = 1.0f
        const val MIN_GLOBAL_ZOOM = 0.25f
        const val MAX_GLOBAL_ZOOM = 3.0f

        /** Default print density. This value is never reduced during export. */
        const val DEFAULT_POSTER_EXPORT_DPI = 350
        const val DEFAULT_POSTER_EXPORT_FORMAT = "PNG"
        const val DEFAULT_POSTER_EXPORT_TRANSPARENT_BACKGROUND = false

        /**
         * Computes an export canvas from the rendered content bounds.
         *
         * The caller supplies content dimensions in logical pixels. Dimensions
         * are not forced to a fixed poster size; only a conservative device
         * safety budget prevents a giant Bitmap allocation from crashing the APK.
         * DPI remains metadata and is deliberately untouched by this guard.
         */
        fun safeExportSize(
            contentWidthPx: Int,
            contentHeightPx: Int,
            maxPixels: Long = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_PIXELS,
            maxDimensionPx: Int = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_DIMENSION_PX,
            dpi: Int = AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_DPI,
        ): ExportPixelSize {
            val width = contentWidthPx.coerceAtLeast(1)
            val height = contentHeightPx.coerceAtLeast(1)
            val pixelBudget = maxPixels.coerceAtLeast(1L)
            val dimension = maxDimensionPx.coerceAtLeast(1)
            val scale = minOf(
                1.0,
                dimension.toDouble() / width.toDouble(),
                dimension.toDouble() / height.toDouble(),
                sqrt(pixelBudget.toDouble() / (width.toDouble() * height.toDouble())),
            )
            return ExportPixelSize(
                width = max(1, (width * scale).toInt()),
                height = max(1, (height * scale).toInt()),
                dpi = dpi.coerceAtLeast(1),
            )
        }

        fun normalizeZoom(zoom: Float): Float =
            if (zoom.isFinite()) zoom.coerceIn(MIN_GLOBAL_ZOOM, MAX_GLOBAL_ZOOM)
            else DEFAULT_GLOBAL_ZOOM

        // Conservative raster safety guard. It is a crash-prevention ceiling,
        // not a fixed output resolution requirement.
        const val DEFAULT_MAX_EXPORT_DIMENSION_PX = 16_384
        const val DEFAULT_MAX_EXPORT_PIXELS = 64L * 1024L * 1024L
    }
}

data class ExportPixelSize(
    val width: Int,
    val height: Int,
    val dpi: Int,
)
