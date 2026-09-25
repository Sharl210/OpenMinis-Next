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
            maxPixels: Long = DEFAULT_MAX_EXPORT_PIXELS,
            maxDimensionPx: Int = DEFAULT_MAX_EXPORT_DIMENSION_PX,
            dpi: Int = DEFAULT_POSTER_EXPORT_DPI,
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
