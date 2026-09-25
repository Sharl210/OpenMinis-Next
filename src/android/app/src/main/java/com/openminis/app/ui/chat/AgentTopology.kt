package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.openminis.app.R
import com.openminis.app.feature.runtime.RuntimeTreeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A pure-data node that can be rendered by a Compose topology view. */
data class AgentTopologyNode(
    val id: String,
    val title: String,
    val summary: String = "",
    val tokens: Long = 0L,
    val parentId: String? = null,
    val width: Float = DEFAULT_NODE_WIDTH,
    val height: Float = DEFAULT_NODE_HEIGHT,
    val depth: Int = 0,
    val createdAtMillis: Long = 0L,
    val globalOrder: Int = 0,
    val status: String = "INACTIVE",
) {
    init {
        require(id.isNotBlank()) { "node id must not be blank" }
        require(width > 0f && height > 0f) { "node dimensions must be positive" }
        require(tokens >= 0L) { "token count must not be negative" }
    }

    companion object {
        const val DEFAULT_NODE_WIDTH = 176f
        const val DEFAULT_NODE_HEIGHT = 92f
    }
}

/** One directed relationship. Reverse edges are intentionally distinct. */
data class AgentTopologyRelation(
    val fromId: String,
    val toId: String,
    val label: String? = null,
) {
    init {
        require(fromId.isNotBlank() && toId.isNotBlank()) { "relation endpoints must not be blank" }
        require(fromId != toId) { "self relations are not supported" }
    }
}

data class AgentTopologyPoint(val x: Float, val y: Float)

data class AgentTopologyRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val center: AgentTopologyPoint get() = AgentTopologyPoint(left + width / 2f, top + height / 2f)

    init {
        require(width >= 0f && height >= 0f) { "rectangle dimensions must not be negative" }
    }
}

data class AgentTopologyNodeLayout(
    val node: AgentTopologyNode,
    val depth: Int,
    val rect: AgentTopologyRect,
    val summaryLines: List<String>,
    val tokenLabel: String,
)

data class AgentTopologyRoutedEdge(
    val relation: AgentTopologyRelation,
    val points: List<AgentTopologyPoint>,
    /** False means no collision-free path was found; callers may render a warning. */
    val collisionFree: Boolean,
)

data class AgentTopologyLayout(
    val nodes: List<AgentTopologyNodeLayout>,
    val edges: List<AgentTopologyRoutedEdge>,
)

data class AgentTopologyLayoutOptions(
    val horizontalGap: Float = 32f,
    val verticalGap: Float = 56f,
    val summaryLines: Int = 3,
    val summaryCharsPerLine: Int = 34,
    val edgeClearance: Float = 8f,
) {
    init {
        require(horizontalGap >= 0f && verticalGap >= 0f) { "layout gaps must not be negative" }
        require(summaryLines in 1..3) { "summaryLines must be between 1 and 3" }
        require(summaryCharsPerLine > 0) { "summaryCharsPerLine must be positive" }
        require(edgeClearance >= 0f) { "edgeClearance must not be negative" }
    }
}

/**
 * Returns at most one edge for each ordered pair. Thus A→B and B→A can both
 * exist, while repeated A→B records collapse to the first record.
 */
fun deduplicateAgentTopologyRelations(
    relations: List<AgentTopologyRelation>,
): List<AgentTopologyRelation> {
    val seen = HashSet<String>(relations.size)
    return relations.filter { relation ->
        seen.add("${relation.fromId}\u0000${relation.toId}")
    }
}

/**
 * Lays nodes out in non-overlapping depth bands. Nodes in each band are placed
 * sequentially, so differing node widths cannot overlap. Edges are routed only
 * after all rectangles are known and are collision checked against every
 * non-endpoint rectangle.
 */
fun layoutAgentTopology(
    nodes: List<AgentTopologyNode>,
    relations: List<AgentTopologyRelation> = emptyList(),
    options: AgentTopologyLayoutOptions = AgentTopologyLayoutOptions(),
): AgentTopologyLayout {
    require(nodes.map { it.id }.toSet().size == nodes.size) { "node ids must be unique" }
    val byId = nodes.associateBy { it.id }
    val depthCache = HashMap<String, Int>()
    val visiting = HashSet<String>()

    fun depthOf(id: String): Int {
        depthCache[id]?.let { return it }
        if (!visiting.add(id)) return 0
        val parent = byId[id]?.parentId
        val depth = if (parent != null && parent in byId) depthOf(parent) + 1 else 0
        visiting.remove(id)
        depthCache[id] = depth
        return depth
    }

    val depths = nodes.associate { it.id to depthOf(it.id) }
    val levels = nodes.withIndex().groupBy { depths[it.value.id] ?: 0 }
    val levelHeights = levels.mapValues { (_, levelNodes) -> levelNodes.maxOf { it.value.height } }
    val levelTop = HashMap<Int, Float>()
    var nextTop = 0f
    levels.keys.sorted().forEach { depth ->
        levelTop[depth] = nextTop
        nextTop += (levelHeights[depth] ?: 0f) + options.verticalGap
    }

    val laidOut = ArrayList<AgentTopologyNodeLayout>(nodes.size)
    levels.keys.sorted().forEach { depth ->
        var nextLeft = 0f
        levels[depth].orEmpty().forEach { indexed ->
            val node = indexed.value
            val rect = AgentTopologyRect(
                left = nextLeft,
                top = levelTop.getValue(depth),
                width = node.width,
                height = node.height,
            )
            laidOut += AgentTopologyNodeLayout(
                node = node,
                depth = depth,
                rect = rect,
                summaryLines = summarizeAgentTopology(node.summary, options.summaryLines, options.summaryCharsPerLine),
                tokenLabel = formatAgentTokens(node.tokens),
            )
            nextLeft += node.width + options.horizontalGap
        }
    }

    val rects = laidOut.associate { it.node.id to it.rect }
    val edges = routeAgentTopologyEdges(
        relations = deduplicateAgentTopologyRelations(relations),
        nodeRects = rects,
        clearance = options.edgeClearance,
    )
    return AgentTopologyLayout(laidOut, edges)
}

/**
 * Wraps a node summary to a bounded number of lines. Newlines are respected;
 * long words are hard-wrapped so Compose never receives more than the limit.
 */
fun summarizeAgentTopology(
    summary: String,
    maxLines: Int = 3,
    maxCharsPerLine: Int = 34,
): List<String> {
    require(maxLines in 1..3) { "maxLines must be between 1 and 3" }
    require(maxCharsPerLine > 0) { "maxCharsPerLine must be positive" }
    val lines = ArrayList<String>(maxLines)
    summary.replace('\r', '\n').split('\n').forEach { rawLine ->
        var remaining = rawLine.trim()
        if (remaining.isEmpty() && lines.isEmpty()) return@forEach
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val take = min(maxCharsPerLine, remaining.length)
            var cut = take
            if (take < remaining.length) {
                val whitespace = remaining.lastIndexOf(' ', take - 1)
                if (whitespace > 0) cut = whitespace
            }
            lines += remaining.take(cut).trimEnd()
            remaining = remaining.drop(cut).trimStart()
        }
        if (lines.size == maxLines) return@forEach
    }
    if (lines.size == maxLines && summary.trim().length > lines.joinToString(" ").length) {
        val lastIndex = lines.lastIndex
        val last = lines[lastIndex]
        lines[lastIndex] = if (maxCharsPerLine == 1) "…" else {
            last.take(maxCharsPerLine - 1).trimEnd() + "…"
        }
    }
    return lines
}

/** Formats a non-negative token count using compact K/M/B units. */
fun formatAgentTokens(tokens: Long): String {
    require(tokens >= 0L) { "token count must not be negative" }
    return when {
        tokens < 1_000L -> tokens.toString()
        tokens < 1_000_000L -> compactTokenUnit(tokens.toDouble() / 1_000.0, "K")
        tokens < 1_000_000_000L -> compactTokenUnit(tokens.toDouble() / 1_000_000.0, "M")
        else -> compactTokenUnit(tokens.toDouble() / 1_000_000_000.0, "B")
    }
}

private fun compactTokenUnit(value: Double, suffix: String): String {
    val decimals = if (value >= 100.0) 0 else 1
    val raw = String.format(Locale.US, "%.${decimals}f", value)
    return raw.trimEnd('0').trimEnd('.') + suffix
}

/**
 * Routes every directed relation through a visibility-grid search. The grid
 * contains both sides of every rectangle, so a cross-level edge can leave the
 * tree, use an outer channel, and re-enter without crossing another node.
 */
fun routeAgentTopologyEdges(
    relations: List<AgentTopologyRelation>,
    nodeRects: Map<String, AgentTopologyRect>,
    clearance: Float = 8f,
): List<AgentTopologyRoutedEdge> {
    require(clearance >= 0f) { "clearance must not be negative" }
    return deduplicateAgentTopologyRelations(relations).mapNotNull { relation ->
        val from = nodeRects[relation.fromId] ?: return@mapNotNull null
        val to = nodeRects[relation.toId] ?: return@mapNotNull null
        val points = routeAgentTopologyEdge(
            relation.fromId,
            relation.toId,
            from.center,
            to.center,
            nodeRects,
            clearance,
        )
        AgentTopologyRoutedEdge(
            relation = relation,
            points = points,
            collisionFree = agentTopologyPathIsClear(
                points,
                relation.fromId,
                relation.toId,
                nodeRects,
                clearance,
            ),
        )
    }
}

private fun routeAgentTopologyEdge(
    fromId: String,
    toId: String,
    start: AgentTopologyPoint,
    end: AgentTopologyPoint,
    rects: Map<String, AgentTopologyRect>,
    clearance: Float,
): List<AgentTopologyPoint> {
    if (agentTopologyPathIsClear(listOf(start, end), fromId, toId, rects, clearance)) {
        return listOf(start, end)
    }

    val xCoordinates = buildSet {
        add(start.x)
        add(end.x)
        rects.values.forEach {
            add(it.left - clearance)
            add(it.right + clearance)
            add(it.center.x)
        }
    }.toList().sorted()
    val yCoordinates = buildSet {
        add(start.y)
        add(end.y)
        rects.values.forEach {
            add(it.top - clearance)
            add(it.bottom + clearance)
            add(it.center.y)
        }
    }.toList().sorted()

    val points = ArrayList<AgentTopologyPoint>(xCoordinates.size * yCoordinates.size + 2)
    yCoordinates.forEach { y -> xCoordinates.forEach { x -> points += AgentTopologyPoint(x, y) } }
    val startIndex = points.indexOf(start).takeIf { it >= 0 } ?: run { points += start; points.lastIndex }
    val endIndex = points.indexOf(end).takeIf { it >= 0 } ?: run { points += end; points.lastIndex }
    val neighbors = Array(points.size) { ArrayList<Int>(4) }

    val rows = points.indices.groupBy { points[it].y }
    rows.values.forEach { row ->
        row.sortedBy { points[it].x }.zipWithNext().forEach { (a, b) ->
            if (agentTopologySegmentIsClear(points[a], points[b], fromId, toId, rects, clearance)) {
                neighbors[a] += b
                neighbors[b] += a
            }
        }
    }
    val columns = points.indices.groupBy { points[it].x }
    columns.values.forEach { column ->
        column.sortedBy { points[it].y }.zipWithNext().forEach { (a, b) ->
            if (agentTopologySegmentIsClear(points[a], points[b], fromId, toId, rects, clearance)) {
                neighbors[a] += b
                neighbors[b] += a
            }
        }
    }

    val previous = IntArray(points.size) { -1 }
    val visited = BooleanArray(points.size)
    val queue = ArrayDeque<Int>()
    queue.add(startIndex)
    visited[startIndex] = true
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current == endIndex) break
        neighbors[current].forEach { next ->
            if (!visited[next]) {
                visited[next] = true
                previous[next] = current
                queue.addLast(next)
            }
        }
    }
    if (visited[endIndex]) {
        val path = ArrayList<AgentTopologyPoint>()
        var cursor = endIndex
        while (cursor >= 0) {
            path += points[cursor]
            if (cursor == startIndex) break
            cursor = previous[cursor]
        }
        path.reverse()
        return simplifyAgentTopologyPath(path)
    }

    // Last resort: a large outer-channel route. Keep the collision flag honest;
    // callers can render a warning if even this channel is blocked by malformed
    // or fully enclosing input rectangles.
    val minLeft = rects.values.minOfOrNull { it.left } ?: min(start.x, end.x)
    val maxRight = rects.values.maxOfOrNull { it.right } ?: max(start.x, end.x)
    val minTop = rects.values.minOfOrNull { it.top } ?: min(start.y, end.y)
    val maxBottom = rects.values.maxOfOrNull { it.bottom } ?: max(start.y, end.y)
    val margin = max(16f, clearance * 2f)
    val candidates = listOf(
        listOf(start, AgentTopologyPoint(minLeft - margin, start.y), AgentTopologyPoint(minLeft - margin, end.y), end),
        listOf(start, AgentTopologyPoint(maxRight + margin, start.y), AgentTopologyPoint(maxRight + margin, end.y), end),
        listOf(start, AgentTopologyPoint(start.x, minTop - margin), AgentTopologyPoint(end.x, minTop - margin), end),
        listOf(start, AgentTopologyPoint(start.x, maxBottom + margin), AgentTopologyPoint(end.x, maxBottom + margin), end),
    )
    return candidates.firstOrNull {
        agentTopologyPathIsClear(it, fromId, toId, rects, clearance)
    } ?: listOf(start, end)
}

/** Returns true when no segment intersects a non-endpoint rectangle. */
fun agentTopologyPathIsClear(
    points: List<AgentTopologyPoint>,
    fromId: String,
    toId: String,
    nodeRects: Map<String, AgentTopologyRect>,
    clearance: Float = 0f,
): Boolean {
    if (points.size < 2) return false
    return points.zipWithNext().all { (a, b) ->
        agentTopologySegmentIsClear(a, b, fromId, toId, nodeRects, clearance)
    }
}

private fun agentTopologySegmentIsClear(
    start: AgentTopologyPoint,
    end: AgentTopologyPoint,
    fromId: String,
    toId: String,
    rects: Map<String, AgentTopologyRect>,
    clearance: Float,
): Boolean = rects.all { (id, rect) ->
    id == fromId || id == toId || !agentTopologySegmentIntersectsRect(start, end, rect, clearance)
}

private fun agentTopologySegmentIntersectsRect(
    start: AgentTopologyPoint,
    end: AgentTopologyPoint,
    rect: AgentTopologyRect,
    clearance: Float,
): Boolean {
    val left = rect.left - clearance
    val right = rect.right + clearance
    val top = rect.top - clearance
    val bottom = rect.bottom + clearance
    var tMin = 0f
    var tMax = 1f
    val dx = end.x - start.x
    val dy = end.y - start.y
    fun clip(p: Float, q: Float): Boolean {
        if (abs(p) < 0.0001f) return q >= 0f
        val r = q / p
        if (p < 0f) {
            if (r > tMax) return false
            if (r > tMin) tMin = r
        } else {
            if (r < tMin) return false
            if (r < tMax) tMax = r
        }
        return true
    }
    return clip(-dx, start.x - left) &&
        clip(dx, right - start.x) &&
        clip(-dy, start.y - top) &&
        clip(dy, bottom - start.y) &&
        tMin <= tMax
}



/** Options for the real topology raster export. */
data class AgentTopologyExportOptions(
    val transparentBackground: Boolean = AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_TRANSPARENT_BACKGROUND,
    val dpi: Int = AgentTopologyPrefs.DEFAULT_POSTER_EXPORT_DPI,
    val maxPixels: Long = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_PIXELS,
    val maxDimensionPx: Int = AgentTopologyPrefs.DEFAULT_MAX_EXPORT_DIMENSION_PX,
) {
    init {
        require(dpi > 0) { "dpi must be positive" }
    }
}

private const val TOPOLOGY_EXPORT_PADDING = 32f

/**
 * Rasterises the actual layout into a PNG. The bitmap bounds follow the layout
 * content rather than a fixed business canvas size; [AgentTopologyPrefs] only
 * applies the crash-safety pixel budget.
 */
fun renderAgentTopologyPng(
    layout: AgentTopologyLayout,
    options: AgentTopologyExportOptions = AgentTopologyExportOptions(),
): ByteArray {
    val bounds = agentTopologyContentBounds(layout, TOPOLOGY_EXPORT_PADDING)
    val size = AgentTopologyPrefs.safeExportSize(
        contentWidthPx = max(1, kotlin.math.ceil(bounds.width).toInt()),
        contentHeightPx = max(1, kotlin.math.ceil(bounds.height).toInt()),
        maxPixels = options.maxPixels,
        maxDimensionPx = options.maxDimensionPx,
        dpi = options.dpi,
    )
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = AndroidCanvas(bitmap)
        if (!options.transparentBackground) canvas.drawColor(android.graphics.Color.WHITE)
        val scaleX = size.width / bounds.width.coerceAtLeast(1f)
        val scaleY = size.height / bounds.height.coerceAtLeast(1f)
        canvas.save()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-bounds.left, -bounds.top)
        drawAgentTopologyAndroid(canvas, layout)
        canvas.restore()
        return encodePngWithDpi(bitmap, size.dpi)
    } finally {
        bitmap.recycle()
    }
}

private fun agentTopologyContentBounds(
    layout: AgentTopologyLayout,
    padding: Float,
): AgentTopologyRect {
    val points = buildList {
        layout.nodes.forEach { node ->
            add(AgentTopologyPoint(node.rect.left, node.rect.top))
            add(AgentTopologyPoint(node.rect.right, node.rect.bottom))
        }
        layout.edges.forEach { edge -> addAll(edge.points) }
    }
    if (points.isEmpty()) return AgentTopologyRect(0f, 0f, padding * 2f, padding * 2f)
    val minX = points.minOf { it.x } - padding
    val minY = points.minOf { it.y } - padding
    val maxX = points.maxOf { it.x } + padding
    val maxY = points.maxOf { it.y } + padding
    return AgentTopologyRect(minX, minY, (maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f))
}

private fun drawAgentTopologyAndroid(canvas: AndroidCanvas, layout: AgentTopologyLayout) {
    val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.rgb(64, 130, 190)
    }
    val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.rgb(245, 247, 250)
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.rgb(72, 83, 102)
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(28, 35, 48)
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(72, 83, 102)
        textSize = 12f
    }
    layout.edges.forEach { edge ->
        if (edge.points.size < 2) return@forEach
        val path = Path().apply {
            moveTo(edge.points.first().x, edge.points.first().y)
            edge.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, edgePaint)
        val before = edge.points[edge.points.lastIndex - 1]
        val tip = edge.points.last()
        val angle = kotlin.math.atan2((tip.y - before.y).toDouble(), (tip.x - before.x).toDouble()).toFloat()
        canvas.save()
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), tip.x, tip.y)
        val arrow = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - 10f, tip.y - 5f)
            moveTo(tip.x, tip.y)
            lineTo(tip.x - 10f, tip.y + 5f)
        }
        canvas.drawPath(arrow, edgePaint)
        canvas.restore()
    }
    layout.nodes.forEach { node ->
        val rect = RectF(node.rect.left, node.rect.top, node.rect.right, node.rect.bottom)
        canvas.drawRoundRect(rect, 12f, 12f, nodePaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)
        val left = node.rect.left + 10f
        var y = node.rect.top + 20f
        canvas.drawText("${node.node.title} · ${node.tokenLabel}", left, y, titlePaint)
        y += 18f
        node.summaryLines.forEach { line ->
            if (y <= node.rect.bottom - 6f) canvas.drawText(line, left, y, bodyPaint)
            y += 15f
        }
    }
}

/**
 * Inserts a PNG pHYs chunk after IHDR. Android's Bitmap.compress does not
 * expose density metadata, so this keeps the user-selected DPI in the actual
 * exported file without changing pixel dimensions.
 */
fun encodePngWithDpi(bitmap: Bitmap, dpi: Int): ByteArray {
    require(dpi > 0) { "dpi must be positive" }
    val raw = ByteArrayOutputStream().also { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG compression failed" }
    }.toByteArray()
    if (raw.size < 33 || raw.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE).not()) return raw
    val pixelsPerMeter = kotlin.math.round(dpi / 0.0254).toLong().coerceIn(1L, 0xFFFF_FFFFL)
    val chunkData = ByteArray(9)
    writeIntBigEndian(chunkData, 0, pixelsPerMeter.toInt())
    writeIntBigEndian(chunkData, 4, pixelsPerMeter.toInt())
    chunkData[8] = 1
    val chunk = pngChunk("pHYs", chunkData)
    val insertion = 8 + 4 + 4 + 13 + 4
    return raw.copyOfRange(0, insertion) + chunk + raw.copyOfRange(insertion, raw.size)
}

private val PNG_SIGNATURE = byteArrayOf(
    137.toByte(), 80, 78, 71, 13, 10, 26, 10,
)

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val output = ByteArrayOutputStream(12 + data.size)
    writeIntBigEndian(output, data.size)
    output.write(typeBytes)
    output.write(data)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }
    writeIntBigEndian(output, crc.value.toInt())
    return output.toByteArray()
}

private fun writeIntBigEndian(output: ByteArrayOutputStream, value: Int) {
    output.write((value ushr 24) and 0xff)
    output.write((value ushr 16) and 0xff)
    output.write((value ushr 8) and 0xff)
    output.write(value and 0xff)
}

private fun writeIntBigEndian(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

data class AgentTopologyGraph(
    val nodes: List<AgentTopologyNode>,
    val relations: List<AgentTopologyRelation>,
    val currentNodeId: String,
)

/** Builds the display graph from the durable runtime tree without changing its schema. */
fun agentTopologyGraphFromRuntimeJson(
    runtimeJson: String,
    sessionId: String,
    sessionTitle: String,
    currentSummary: String,
    currentTokens: Long = 0L,
): AgentTopologyGraph {
    require(sessionId.isNotBlank())
    val runtimeNodes = runCatching {
        val array = JSONObject(runtimeJson).optJSONArray("nodes") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                add(
                    RuntimeTopologyItem(
                        id = id,
                        parentId = json.optString("parentId").takeUnless { it.isBlank() || it == "null" },
                        rootId = json.optString("rootId"),
                        depth = json.optInt("depth"),
                        status = json.optString("status", "INACTIVE"),
                        createdAtMillis = json.optLong("createdAtMillis"),
                        model = json.optJSONObject("model")?.optString("model").orEmpty(),
                        note = json.optJSONObject("model")?.optString("note").orEmpty(),
                        order = index,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
    val root = runtimeNodes
        .filter { it.parentId == null && (it.id == sessionId || it.id.startsWith("$sessionId#run-")) }
        .maxByOrNull(RuntimeTopologyItem::createdAtMillis)
    if (root == null) {
        val node = AgentTopologyNode(
            id = sessionId,
            title = sessionTitle.ifBlank { "Session" },
            summary = currentSummary,
            tokens = currentTokens,
            globalOrder = 0,
            status = "RUNNING",
        )
        return AgentTopologyGraph(listOf(node), emptyList(), sessionId)
    }
    val included = LinkedHashSet<String>().apply { add(root.id) }
    val queue = ArrayDeque<String>().apply { add(root.id) }
    while (queue.isNotEmpty()) {
        val parentId = queue.removeFirst()
        runtimeNodes.filter { it.parentId == parentId }.forEach { child ->
            if (included.add(child.id)) queue.addLast(child.id)
        }
    }
    val graphItems = runtimeNodes.filter { it.id in included }
    val displayNodes = graphItems.map { item ->
        AgentTopologyNode(
            id = item.id,
            title = if (item.id == root.id) sessionTitle.ifBlank { "Session" }
                else item.model.ifBlank { "Agent ${item.depth}" },
            summary = if (item.id == root.id && currentSummary.isNotBlank()) currentSummary else item.note,
            tokens = if (item.id == root.id) currentTokens else 0L,
            parentId = item.parentId,
            depth = item.depth,
            createdAtMillis = item.createdAtMillis,
            globalOrder = item.order,
            status = item.status,
        )
    }
    val ids = displayNodes.mapTo(HashSet()) { it.id }
    val relations = displayNodes.mapNotNull { item ->
        item.parentId?.takeIf(ids::contains)?.let { AgentTopologyRelation(it, item.id, "parent") }
    }
    return AgentTopologyGraph(displayNodes, relations, root.id)
}

private data class RuntimeTopologyItem(
    val id: String,
    val parentId: String?,
    val rootId: String,
    val depth: Int,
    val status: String,
    val createdAtMillis: Long,
    val model: String,
    val note: String,
    val order: Int,
)

@Composable
fun AgentTopologyRoute(
    sessionId: String,
    sessionTitle: String,
    currentSummary: String,
    chatRepository: com.openminis.app.data.repository.ChatRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var graph by remember(sessionId) {
        mutableStateOf(AgentTopologyGraph(emptyList(), emptyList(), sessionId))
    }
    LaunchedEffect(sessionId, sessionTitle, currentSummary) {
        graph = withContext(Dispatchers.IO) {
            val runtimeJson = runCatching {
                RuntimeTreeStore.open(context.applicationContext).snapshot().toJson()
            }.getOrDefault("{}")
            val tokenCount = runCatching {
                chatRepository.sessionTokenUsages(sessionId).sumOf { raw ->
                    val json = JSONObject(raw)
                    json.optLong("inputTokens") + json.optLong("outputTokens")
                }
            }.getOrDefault(0L)
            agentTopologyGraphFromRuntimeJson(
                runtimeJson = runtimeJson,
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                currentSummary = currentSummary,
                currentTokens = tokenCount,
            )
        }
    }
    AgentTopologyScreen(
        nodes = graph.nodes,
        relations = graph.relations,
        currentNodeId = graph.currentNodeId,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/** Full-screen topology canvas. Nodes are rendered, never draggable. */
private fun simplifyAgentTopologyPath(path: List<AgentTopologyPoint>): List<AgentTopologyPoint> {
    if (path.size < 3) return path
    val result = ArrayList<AgentTopologyPoint>(path.size)
    path.forEach { point ->
        if (result.size < 2) {
            result += point
        } else {
            val previous = result[result.lastIndex]
            val before = result[result.lastIndex - 1]
            val sameX = before.x == previous.x && previous.x == point.x
            val sameY = before.y == previous.y && previous.y == point.y
            if (sameX || sameY) result[result.lastIndex] = point else result += point
        }
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTopologyScreen(
    nodes: List<AgentTopologyNode>,
    relations: List<AgentTopologyRelation>,
    currentNodeId: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { AgentTopologyPrefs(context) }
    var zoom by remember { mutableStateOf(prefs.loadZoom()) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var transparent by remember { mutableStateOf(false) }
    val layout = remember(nodes, relations) { layoutAgentTopology(nodes, relations) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(renderAgentTopologyPng(layout, AgentTopologyExportOptions(transparentBackground = transparent)))
                }
            }
        }
    }
    LaunchedEffect(viewport, currentNodeId, layout.nodes.size) {
        val current = layout.nodes.firstOrNull { it.node.id == currentNodeId } ?: layout.nodes.firstOrNull()
        if (current != null && viewport != IntSize.Zero) {
            val center = Offset(current.rect.center.x, current.rect.center.y)
            pan = Offset(viewport.width / 2f - center.x * zoom, viewport.height / 2f - center.y * zoom)
        }
    }
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_topology_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("agent-topology.png") }) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.agent_topology_export))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = prefs.saveZoom((zoom * zoomChange).coerceIn(AgentTopologyPrefs.MIN_GLOBAL_ZOOM, AgentTopologyPrefs.MAX_GLOBAL_ZOOM))
                        pan += panChange
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = pan.x
                        translationY = pan.y
                        scaleX = zoom
                        scaleY = zoom
                    },
            ) {
                layout.edges.forEach { edge ->
                    if (edge.points.size >= 2) {
                        val points = edge.points
                        for (index in 0 until points.lastIndex) {
                            drawLine(
                                color = Color(0xFF4082BE),
                                start = Offset(points[index].x, points[index].y),
                                end = Offset(points[index + 1].x, points[index + 1].y),
                                strokeWidth = 3f,
                            )
                        }
                    }
                }
                layout.nodes.forEach { item ->
                    val topLeft = Offset(item.rect.left, item.rect.top)
                    drawRoundRect(
                        color = surfaceColor,
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(item.rect.width, item.rect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                    )
                    drawRoundRect(
                        color = outlineColor,
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(item.rect.width, item.rect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                        style = Stroke(width = 2f),
                    )
                    val native = drawContext.canvas.nativeCanvas
                    val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(28, 35, 48)
                        textSize = 16f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(72, 83, 102)
                        textSize = 12f
                    }
                    var textY = item.rect.top + 20f
                    native.drawText("${item.node.title} · ${item.tokenLabel}", item.rect.left + 10f, textY, titlePaint)
                    textY += 18f
                    item.summaryLines.forEach { line ->
                        if (textY <= item.rect.bottom - 6f) native.drawText(line, item.rect.left + 10f, textY, bodyPaint)
                        textY += 15f
                    }
                }
            }
            if (layout.nodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_topology_empty),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.FilterChip(
                    selected = transparent,
                    onClick = { transparent = !transparent },
                    label = { Text(stringResource(R.string.agent_topology_transparent)) },
                )
                FloatingActionButton(onClick = { exportLauncher.launch("agent-topology.png") }) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.agent_topology_export))
                }
            }
        }
    }
}
