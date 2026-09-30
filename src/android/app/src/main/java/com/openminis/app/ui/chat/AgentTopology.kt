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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton

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
        /**
         * [T-android-topology-card-layout] Tall enough for the card the
         * requirement describes: three header lines (level·tokens, creation
         * time, global order), the horizontal divider, then three lines of body
         * ("这里用来展示的可以展示3行").
         *
         * It was 92f, which fit exactly two body lines once the creation-time and
         * global-order lines were added — the card would have silently dropped
         * the third line the requirement asks for. Baselines start at top+20f and
         * step 18f then 15f, so the last of three body lines sits at top+98f and
         * the guard stops drawing at bottom-6f: 112f leaves that margin.
         */
        const val DEFAULT_NODE_HEIGHT = 112f
    }
}

/**
 * [T-android-topology-peer-edges] What a line in the diagram MEANS.
 *
 * The runtime has always recorded more than parent/child links
 * (`RuntimeEdgeKind.PARENT_CHILD / SUBSCRIPTION / TEAM_PEER`, exposed in the
 * runtime snapshot as `topologyEdges`), and the requirement asks for the
 * peer-to-peer structure to be visible: "团队模式的话，他们就是 P2P 的节点…
 * 从和从之间可以 P2P 也就是可以拥有更多的一些拓扑关系和连接关系和交流"
 * (request.md:33).
 *
 * The diagram previously drew ONLY parent/child lines — the `topologyEdges`
 * array was never read on this side — so a Team's peer links and any
 * subscriptions were invisible even though the data existed. Drawing them is
 * what makes the P2P topology the requirement describes observable.
 */
/**
 * [T-android-topology-peer-edges] How one edge kind is drawn.
 *
 * Single source of truth: both renderers (the on-screen Compose canvas and the
 * PNG export) consult this, so a change here cannot make the screen and the
 * export disagree — the failure mode this file has already been bitten by when
 * each renderer assembled its own card by hand.
 *
 * Colours are ARGB so the export (`android.graphics`) and Compose (`Color(...)`)
 * can share one value.
 */
internal data class TopologyEdgeStyle(
    val argb: Int,
    val strokeWidth: Float,
    /** null = solid line. */
    val dashIntervals: FloatArray?,
)

/**
 * [T-android-topology-peer-edges] Delegation is the solid backbone; the two
 * non-tree link kinds are dashed and differently coloured so a peer link is not
 * mistaken for a delegation link at a glance.
 */
internal fun topologyEdgeStyle(kind: AgentTopologyEdgeKind): TopologyEdgeStyle = when (kind) {
    AgentTopologyEdgeKind.PARENT -> TopologyEdgeStyle(0xFF4082BE.toInt(), 3f, null)
    AgentTopologyEdgeKind.PEER -> TopologyEdgeStyle(0xFF7A4FC4.toInt(), 3f, floatArrayOf(14f, 9f))
    AgentTopologyEdgeKind.SUBSCRIPTION -> TopologyEdgeStyle(0xFF8A9099.toInt(), 2f, floatArrayOf(4f, 7f))
}

enum class AgentTopologyEdgeKind {
    /** Delegation: who commissioned whom. The tree's backbone. */
    PARENT,

    /** Team peer-to-peer link between two sub-agents. */
    PEER,

    /** Traditional sub-agent subscription/notification link. */
    SUBSCRIPTION,
}

/** One directed relationship. Reverse edges are intentionally distinct. */
data class AgentTopologyRelation(
    val fromId: String,
    val toId: String,
    val label: String? = null,
    val kind: AgentTopologyEdgeKind = AgentTopologyEdgeKind.PARENT,
) {
    init {
        require(fromId.isNotBlank() && toId.isNotBlank()) { "relation endpoints must not be blank" }
        require(fromId != toId) { "self relations are not supported" }
    }
}

data class AgentTopologyPoint(val x: Float, val y: Float)

/**
 * [T-android-topology-arrow-parity] The arrowhead at the END of a routed edge, as
 * drawable segments.
 *
 * WHY THIS IS SHARED AND NOT INLINED TWICE: the PNG exporter drew an arrowhead and
 * the on-screen canvas did not, so the exported image showed edge direction while
 * the screen — the surface users actually look at — showed none. A→B and B→A were
 * visually identical on a device. The two renderers had each grown their own edge
 * drawing (this file already had the same problem with stroke colour and dash
 * pattern, solved by [topologyEdgeStyle]); the geometry is now owned here so the
 * next divergence has to be a deliberate edit to one function.
 *
 * Returns TWO segments (the two barbs), each as (tip, barbEnd). The caller draws
 * them with its own stroke; keeping this free of `Canvas`/`DrawScope` types is what
 * lets both renderers and a JVM test share it.
 *
 * The maths mirrors the exporter's original `canvas.rotate(angle)` + local
 * (-length, ±halfWidth) barbs, expressed as explicit rotation so no canvas state is
 * needed:
 *   barb = tip + rotate((-length, ±halfWidth), angle)
 *
 * Returns an empty list when there is no direction to show (fewer than two points).
 */
internal fun topologyArrowheadSegments(
    points: List<AgentTopologyPoint>,
    length: Float = TOPOLOGY_ARROW_LENGTH,
    halfWidth: Float = TOPOLOGY_ARROW_HALF_WIDTH,
): List<Pair<AgentTopologyPoint, AgentTopologyPoint>> {
    if (points.size < 2) return emptyList()
    val tip = points.last()
    val before = points[points.size - 2]
    val angle = kotlin.math.atan2((tip.y - before.y).toDouble(), (tip.x - before.x).toDouble())
    val cos = kotlin.math.cos(angle).toFloat()
    val sin = kotlin.math.sin(angle).toFloat()
    fun barb(sign: Float): Pair<AgentTopologyPoint, AgentTopologyPoint> {
        val wx = -length
        val wy = halfWidth * sign
        return tip to AgentTopologyPoint(
            x = tip.x + wx * cos - wy * sin,
            y = tip.y + wx * sin + wy * cos,
        )
    }
    return listOf(barb(1f), barb(-1f))
}

/** Arrowhead length in logical pixels; matches the exporter's original barb. */
internal const val TOPOLOGY_ARROW_LENGTH = 10f

/** Arrowhead half-spread in logical pixels; matches the exporter's original barb. */
internal const val TOPOLOGY_ARROW_HALF_WIDTH = 5f


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

/**
 * Formats a non-negative token count using compact K/M/B units.
 *
 * [T-agent-topology-token-unit-bug] The unit boundaries are chosen so the
 * rounded label never crosses into the next unit, and [compactTokenUnit] only
 * strips trailing zeros when a decimal point is actually present.
 *
 * Both halves matter. The old code formatted with `%.0f` for values ≥100 (which
 * yields a plain integer such as "200") and then unconditionally ran
 * `trimEnd('0')` — turning "200" into "2", i.e. **200 000 tokens displayed as
 * "2K", a 100× understatement** (999 999 showed as "1K", ~1000×). It also
 * rounded 999 999 to "1000K" territory and then trimmed it to "1K". The
 * requirement asks for the "238.4K/M/B" shape, so one decimal is kept and the
 * promotion thresholds account for rounding.
 */
fun formatAgentTokens(tokens: Long): String {
    require(tokens >= 0L) { "token count must not be negative" }
    return when {
        tokens < 1_000L -> tokens.toString()
        // 999_950/1000 = 999.95 → "1000.0" would leave the K range after
        // rounding, so promote earlier and keep the label monotonic.
        tokens < 999_950L -> compactTokenUnit(tokens.toDouble() / 1_000.0, "K")
        tokens < 999_950_000L -> compactTokenUnit(tokens.toDouble() / 1_000_000.0, "M")
        else -> compactTokenUnit(tokens.toDouble() / 1_000_000_000.0, "B")
    }
}

private fun compactTokenUnit(value: Double, suffix: String): String {
    val raw = String.format(Locale.US, "%.1f", value)
    // Only trim when there is a fractional part to trim. Stripping zeros from a
    // whole number deletes magnitude ("200" → "2"); stripping "238.4" or
    // "200.0" of redundant zeros is cosmetic and safe.
    val trimmed = if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
    return trimmed + suffix
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
    // [T-android-topology-edge-clearance] Escalate the channel margin before giving
    // up. The requirement is absolute — "所有的线不能够被节点遮挡住…就要绕过节点"
    // (request.md:35) — and it explains why: an occluded stretch turns into a black
    // box where the user cannot tell which line is which. A single margin used to
    // mean that when one channel width happened to be blocked, the router fell
    // straight through to the last resort below and drew a line through the nodes.
    //
    // Trying successively wider channels costs a handful of cheap segment tests and
    // keeps the collision flag honest: it is now false only when every width failed,
    // not merely the first one.
    for (factor in listOf(1f, 2f, 4f, 8f)) {
        val margin = max(16f, clearance * 2f) * factor
        val candidates = listOf(
            listOf(start, AgentTopologyPoint(minLeft - margin, start.y), AgentTopologyPoint(minLeft - margin, end.y), end),
            listOf(start, AgentTopologyPoint(maxRight + margin, start.y), AgentTopologyPoint(maxRight + margin, end.y), end),
            listOf(start, AgentTopologyPoint(start.x, minTop - margin), AgentTopologyPoint(end.x, minTop - margin), end),
            listOf(start, AgentTopologyPoint(start.x, maxBottom + margin), AgentTopologyPoint(end.x, maxBottom + margin), end),
        )
        candidates.firstOrNull {
            agentTopologyPathIsClear(it, fromId, toId, rects, clearance)
        }?.let { return it }
    }
    // Genuinely boxed in (malformed or fully enclosing rectangles). A straight line is
    // the only route left; `collisionFree` is set to false for this path, which is the
    // one case where the requirement cannot be honoured.
    return listOf(start, end)
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
    /** [T-android-topology-i18n] Localized card labels; see TopologyCardStrings. */
    val cardStrings: TopologyCardStrings = DEFAULT_TOPOLOGY_CARD_STRINGS,
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
        drawAgentTopologyAndroid(canvas, layout, options.cardStrings)
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

private fun drawAgentTopologyAndroid(
    canvas: AndroidCanvas,
    layout: AgentTopologyLayout,
    cardStrings: TopologyCardStrings,
) {
    // [T-android-topology-peer-edges] One paint per edge kind, built from the
    // shared style so the export matches the screen.
    val edgePaints = AgentTopologyEdgeKind.entries.associateWith { kind ->
        val style = topologyEdgeStyle(kind)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = style.strokeWidth
            color = style.argb
            style.dashIntervals?.let { pathEffect = android.graphics.DashPathEffect(it, 0f) }
        }
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
    /** [T-android-topology-card-layout] The divider the requirement asks for. */
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = android.graphics.Color.rgb(210, 216, 226)
    }
    layout.edges.forEach { edge ->
        if (edge.points.size < 2) return@forEach
        val edgePaint = edgePaints.getValue(edge.relation.kind)
        val path = Path().apply {
            moveTo(edge.points.first().x, edge.points.first().y)
            edge.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, edgePaint)
        // [T-android-topology-arrow-parity] Shared geometry (see
        // topologyArrowheadSegments): this used to be a canvas rotate + local-coord
        // Path, which the on-screen renderer had no equivalent of.
        topologyArrowheadSegments(edge.points).forEach { (tip, barb) ->
            canvas.drawLine(tip.x, tip.y, barb.x, barb.y, edgePaint)
        }
    }
    layout.nodes.forEach { node ->
        val rect = RectF(node.rect.left, node.rect.top, node.rect.right, node.rect.bottom)
        canvas.drawRoundRect(rect, 12f, 12f, nodePaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)
        val left = node.rect.left + 10f
        var y = node.rect.top + 20f
        // [T-android-topology-card-layout] Shared card lines, in the order the
        // requirement fixes: level·tokens, creation time, global order, divider,
        // body. The screen canvas draws the SAME list — previously each renderer
        // assembled this by hand and the two disagreed.
        val card = topologyCardLines(node.node, node.tokenLabel, cardStrings)
        canvas.drawText(card.title, left, y, titlePaint)
        y += 18f
        canvas.drawText(card.created, left, y, bodyPaint)
        y += 15f
        canvas.drawText(card.order, left, y, bodyPaint)
        y += 15f
        // Horizontal divider between header and body.
        canvas.drawLine(left, y - 10f, node.rect.right - 10f, y - 10f, dividerPaint)
        node.summaryLines.forEach { line ->
            if (y <= node.rect.bottom - 6f) canvas.drawText(line, left, y, bodyPaint)
            y += 15f
        }
    }
}

/**
 * Fallback labels for callers without a Context (JVM tests, and any future
 * headless export). Mirrors the English resources; the UI always passes the
 * localized set explicitly.
 */
internal val DEFAULT_TOPOLOGY_CARD_STRINGS = TopologyCardStrings(
    mainAgent = "Main agent",
    subAgentTemplate = "Level-%1\$s sub-agent",
    levelNumerals = (1..10).map(Int::toString),
    createdKnown = "Created %1\$s",
    createdUnknown = "Creation time unknown",
    order = "Global order: %1\$d",
)

/** [T-android-topology-i18n] Resolve the card labels from resources. */
internal fun topologyCardStrings(context: android.content.Context): TopologyCardStrings =
    TopologyCardStrings(
        mainAgent = context.getString(R.string.agent_topology_level_main),
        subAgentTemplate = context.getString(R.string.agent_topology_level_sub),
        levelNumerals = context.resources
            .getStringArray(R.array.agent_topology_level_numerals)
            .toList(),
        createdKnown = context.getString(R.string.agent_topology_created_known),
        createdUnknown = context.getString(R.string.agent_topology_created_unknown),
        order = context.getString(R.string.agent_topology_order),
    )

/**
 * [T-android-topology-level-label] The card TITLE: which level of sub-agent this
 * node is.
 *
 * The requirement is explicit that the level name IS the title — "作为它的标题，
 * 它是几级子代理 … 主代理，就是第一层，然后第二层就是一级子代理" — and that
 * siblings are told apart by a numeric suffix: "每一层可能会有多个子代理，那么多个
 * 子代理就是比如说二级子代理-1，-2以此类推".
 *
 * The previous title was `sessionTitles[id] ?: model.ifBlank { "Agent ${depth}" }`,
 * so the user read implementation-side identifiers ("Agent 1", or a raw model id)
 * where the requirement asks for the tree position. The session/model name is not
 * lost: it becomes the body fallback when the node has no message yet (see
 * `agentTopologyGraphFromRuntimeJson`).
 *
 * @param siblingIndex 1-based position among agents sharing this node's parent —
 *   i.e. the "-1, -2" in "二级子代理-1". Siblings are the set the user
 *   distinguishes on screen; nodes under different parents are already told apart
 *   by their parent, and every node additionally carries a global order number.
 *   Children are numbered unconditionally (see the note at the return) so a
 *   label never changes just because a sibling appeared. If the product ever
 *   wants the numbering to run across a whole level instead of per parent, only
 *   this function and `topologySiblingIndexes` change.
 */
internal fun topologyLevelLabel(depth: Int, siblingIndex: Int, strings: TopologyCardStrings): String {
    val level = if (depth <= 0) {
        strings.mainAgent
    } else {
        // Counting is 1-based from the first level BELOW the root: depth 1 is
        // "一级子代理". Past the supplied numerals the digit is used rather than a
        // made-up name.
        val numeral = strings.levelNumerals.getOrNull(depth - 1) ?: depth.toString()
        java.lang.String.format(java.util.Locale.ROOT, strings.subAgentTemplate, numeral)
    }
    // Children are ALWAYS numbered, including a lone one. The requirement's own
    // example is "二级子代理-1，-2", so "-1" is the expected first form; and
    // suppressing the suffix for an only child would make the label unstable —
    // a node would be renamed the moment a sibling appeared.
    return if (depth <= 0) level else "$level-${siblingIndex.coerceAtLeast(1)}"
}

/**
 * [T-android-topology-i18n] Every user-visible string the card needs, resolved
 * from resources by the caller.
 *
 * These labels were hardcoded Chinese in this file — the exact defect the
 * requirement calls out ("加入的资源也要支持多国语言啊不是直接硬编码"). Passing them
 * in keeps the builders pure and JVM-testable while letting the UI localize;
 * tests supply explicit strings, so they assert the SHAPE rather than whatever
 * locale happens to be active.
 */
data class TopologyCardStrings(
    val mainAgent: String,
    /** `%1$s` = the level numeral/designator, e.g. 一 or 1. */
    val subAgentTemplate: String,
    val levelNumerals: List<String>,
    /** `%1$s` = the formatted creation time. */
    val createdKnown: String,
    val createdUnknown: String,
    /** `%1$d` = the global order. */
    val order: String,
)

/**
 * [T-android-topology-level-label] The title line, shared by BOTH renderers.
 *
 * It used to be hand-written identically in the on-screen canvas and the PNG
 * export. Two copies of one concept drift, and these two already had: the screen
 * card drew title+token+body while the export drew an extra metadata line, so the
 * creation time and global order the requirement asks for were visible ONLY in
 * the exported file. Sharing the builders makes that divergence impossible
 * rather than merely unlikely.
 */
internal fun topologyTitleLine(title: String, tokenLabel: String): String = "$title · $tokenLabel"

/** Second line: when the node was created. */
internal fun topologyCreatedLabel(createdAtMillis: Long, strings: TopologyCardStrings): String =
    if (createdAtMillis > 0L) {
        java.lang.String.format(java.util.Locale.ROOT, strings.createdKnown, formatTopologyTime(createdAtMillis))
    } else {
        strings.createdUnknown
    }

/** Third line: the global creation index — "第三行就是的全局序号". */
internal fun topologyOrderLabel(globalOrder: Int, strings: TopologyCardStrings): String =
    java.lang.String.format(java.util.Locale.ROOT, strings.order, globalOrder)

/**
 * 1-based sibling position for every node, keyed by node id.
 *
 * Ordered by `globalOrder` (creation order) rather than by list position, so a
 * node's suffix never changes because an unrelated node was inserted earlier in
 * the array. Nodes whose parent is absent from the set are numbered among the
 * nodes that share the same depth, keeping the label defined for a partial tree
 * (e.g. a child included while its parent was filtered out).
 */
internal fun topologySiblingIndexes(nodes: List<AgentTopologyNode>): Map<String, Int> {
    val byParent = LinkedHashMap<String, MutableList<AgentTopologyNode>>()
    val orphaned = LinkedHashMap<Int, MutableList<AgentTopologyNode>>()
    val ids = nodes.mapTo(HashSet()) { it.id }
    for (node in nodes) {
        if (node.depth <= 0) continue
        val parent = node.parentId
        if (parent != null && parent in ids) {
            byParent.getOrPut(parent) { mutableListOf() }.add(node)
        } else {
            orphaned.getOrPut(node.depth) { mutableListOf() }.add(node)
        }
    }
    val result = HashMap<String, Int>(nodes.size)
    for (group in byParent.values) {
        group.sortedBy { it.globalOrder }.forEachIndexed { i, n -> result[n.id] = i + 1 }
    }
    for (group in orphaned.values) {
        group.sortedBy { it.globalOrder }.forEachIndexed { i, n -> result[n.id] = i + 1 }
    }
    return result
}

/**
 * [T-android-topology-card-layout] The lines a node card draws, built once and
 * consumed by BOTH renderers.
 *
 * This is the fix for the "two renderers" defect: the on-screen canvas and the
 * PNG export each assembled the card by hand, and they had already diverged. The
 * screen showed only `title · tokens` followed by the body, while the export
 * added a metadata line — so the creation time and global order the requirement
 * asks for ("第二行就是它被创建的时间，第三行就是的全局序号") existed solely in the
 * exported PNG and were INVISIBLE in the canvas the user actually looks at.
 *
 * Returning plain strings keeps it JVM-testable: the requirement fixed the card's
 * content and line order, and that is exactly what these assertions can pin
 * without a Canvas, a Bitmap or a Compose runtime.
 */
internal data class TopologyCardLines(
    /** Line 1: level name + " · " + token count. */
    val title: String,
    /** Line 2: creation time. */
    val created: String,
    /** Line 3: global creation index. */
    val order: String,
    /**
     * The three lines above the divider, in draw order. Renderers draw exactly
     * this list and then the body, so the line order cannot drift between them.
     */
    val header: List<String>,
)

internal fun topologyCardLines(
    node: AgentTopologyNode,
    tokenLabel: String,
    strings: TopologyCardStrings,
): TopologyCardLines {
    val title = topologyTitleLine(node.title, tokenLabel)
    val created = topologyCreatedLabel(node.createdAtMillis, strings)
    val order = topologyOrderLabel(node.globalOrder, strings)
    return TopologyCardLines(title = title, created = created, order = order, header = listOf(title, created, order))
}

private fun formatTopologyTime(timestampMillis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(timestampMillis))

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

fun aggregateAgentTopologyTokens(
    sessionIds: Set<String>,
    usageRows: List<com.openminis.app.data.db.SessionTokenUsageRow>,
): Map<String, Long> = usageRows
    .asSequence()
    .filter { it.sessionId in sessionIds }
    .mapNotNull { row ->
        runCatching {
            val json = JSONObject(row.tokenUsage)
            row.sessionId to (json.optLong("inputTokens") + json.optLong("outputTokens"))
        }.getOrNull()
    }
    .groupingBy { it.first }
    .fold(0L) { total, entry -> total + entry.second }

/**
 * [T-android-topology-run-root-keys] Re-key per-session data onto runtime NODE ids.
 *
 * THE BUG THIS EXISTS FOR: after a conversation's first run finishes, the runtime
 * tree roots every subsequent run at `"<sessionId>#run-<n>"` (`RuntimeSessionCoordinator
 * .createRoot`), while chat messages and token rows live under the plain
 * conversation id. Three lookups here were keyed by node id, so on every run after
 * the first they all silently missed:
 *
 *  - the node body's first message fell back to `currentSummary` — i.e. the LATEST
 *    assistant reply, which is precisely what the requirement replaced it with
 *    ("就是这个节点所对应的对话内容的第一条消息", request.md:37);
 *  - `aggregateAgentTopologyTokens` filters `row.sessionId in sessionIds`, so the
 *    conversation's rows were dropped and the root node showed **0 tokens**;
 *  - session titles came back null.
 *
 * Folding happens through [com.openminis.app.data.repository.canonicalRuntimeSessionId]
 * rather than a second copy of the `#run-` rule: that helper already owns the
 * definition (including the deliberate refusal to strip a non-numeric suffix), and
 * duplicating it here is how the two would drift.
 *
 * @param nodeIds runtime tree node ids (what the layout and graph are built from).
 * @param valuesBySessionId values fetched using canonical conversation ids.
 * @return the same values keyed by node id, for the callers that index by node.
 *   A node whose canonical id has no value is simply absent, so "unknown" stays
 *   distinguishable from "empty".
 */
internal fun <T> remapRuntimeNodeKeys(
    nodeIds: Set<String>,
    valuesBySessionId: Map<String, T>,
): Map<String, T> = buildMap {
    nodeIds.forEach { nodeId ->
        val sessionId = com.openminis.app.data.repository.canonicalRuntimeSessionId(nodeId)
        valuesBySessionId[sessionId]?.let { put(nodeId, it) }
    }
}

/** Builds the display graph from the durable runtime tree without changing its schema. */
internal fun agentTopologyGraphFromRuntimeJson(
    runtimeJson: String,
    sessionId: String,
    sessionTitle: String,
    currentSummary: String,
    currentTokens: Long = 0L,
    sessionTitles: Map<String, String> = emptyMap(),
    cardStrings: TopologyCardStrings = DEFAULT_TOPOLOGY_CARD_STRINGS,
    tokenTotalsBySession: Map<String, Long> = emptyMap(),
    /**
     * [T-android-topology-first-message] Each node's OPENING message text, keyed by
     * session id. This is what the requirement puts in the node body: "就是这个节点
     * 所对应的对话内容的第一条消息" (request.md:37). Missing key = the session has no
     * messages yet, which is different from "has an empty message".
     */
    firstMessagesBySession: Map<String, String> = emptyMap(),
): AgentTopologyGraph {
    require(sessionId.isNotBlank())
    // [T-android-topology-peer-edges] Peer/subscription links the runtime already
    // records. Parsed here (not in the UI) so the mapping stays pure and testable.
    val runtimeLinks = parseRuntimeTopologyLinks(runtimeJson)
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
                        // 1-based on purpose. The requirement defines this number as
                        // "这个序号就是全局的索引号，就是它的第几个被创建的"
                        // (request.md:37) and shows it as "全局序号: 3" — both are the
                        // Chinese ordinal "第几个", which starts at 1. The array position
                        // is a 0-based index, so without the +1 the first node created
                        // (the root) would be labelled "全局序号: 0".
                        //
                        // `nodes` is serialised from a LinkedHashMap in insertion order,
                        // so this index IS creation order; only the base changes here.
                        //
                        // Sorting is unaffected: `topologySiblingIndexes` sorts by this
                        // value and a constant offset cannot reorder. Nothing else reads
                        // `globalOrder` — it is display-only and is not exported.
                        order = index + 1,
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
            // Same 1-based convention as the parsed path above; this synthetic node
            // stands in for "the first node of this session".
            globalOrder = 1,
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
    // [T-android-topology-level-label] The card title is the node's POSITION in
    // the tree ("主代理" / "一级子代理" / "二级子代理-1"), per the requirement.
    // The session/model name it used to show is not dropped — it moves to the
    // body so a node with no message yet still says something identifiable.
    val siblingIndexes = topologySiblingIndexes(
        graphItems.map { item ->
            AgentTopologyNode(
                id = item.id,
                title = "",
                parentId = item.parentId,
                depth = item.depth,
                globalOrder = item.order,
            )
        },
    )
    val displayNodes = graphItems.map { item ->
        AgentTopologyNode(
            id = item.id,
            title = topologyLevelLabel(item.depth, siblingIndexes[item.id] ?: 1, cardStrings),
            // [T-android-topology-first-message] The body is the conversation's
            // FIRST message, per the requirement. The chain below it is a fallback
            // for a node whose session has no rows yet (a child created but not yet
            // spoken to) — it is deliberately NOT allowed to outrank the first
            // message, which is what used to happen: the box showed a session title
            // or the raw model id instead of the line the user is looking for.
            summary = when {
                firstMessagesBySession[item.id]?.isNotBlank() == true ->
                    firstMessagesBySession.getValue(item.id)
                // Root-only fallback, and only when the session has no message row
                // yet: the first turn can still be streaming, so nothing has been
                // persisted and there IS no first message to show. Kept BELOW the
                // first-message branch deliberately — otherwise a session with
                // history would keep showing its latest reply, which is not the
                // "第一条消息" the requirement asks for.
                item.id == root.id && currentSummary.isNotBlank() -> currentSummary
                item.note.isNotBlank() -> item.note
                // Legacy fallback, previously the title: still shown, just not as
                // the heading.
                else -> sessionTitles[item.id] ?: item.model
            },
            tokens = if (item.id == root.id) currentTokens else tokenTotalsBySession[item.id] ?: 0L,
            parentId = item.parentId,
            depth = item.depth,
            createdAtMillis = item.createdAtMillis,
            globalOrder = item.order,
            status = item.status,
        )
    }
    val ids = displayNodes.mapTo(HashSet()) { it.id }
    val parentRelations = displayNodes.mapNotNull { item ->
        item.parentId?.takeIf(ids::contains)?.let {
            AgentTopologyRelation(it, item.id, "parent", AgentTopologyEdgeKind.PARENT)
        }
    }
    // Runtime links are added only when both endpoints are on screen, and a
    // PARENT_CHILD link is skipped because the parentId above already produced it
    // — drawing both would double-stroke the same delegation line.
    val existing = parentRelations.mapTo(HashSet()) { Triple(it.fromId, it.toId, it.kind) }
    val linkRelations = runtimeLinks.mapNotNull { link ->
        if (link.fromId !in ids || link.toId !in ids) return@mapNotNull null
        if (link.kind == AgentTopologyEdgeKind.PARENT) return@mapNotNull null
        if (!existing.add(Triple(link.fromId, link.toId, link.kind))) return@mapNotNull null
        AgentTopologyRelation(link.fromId, link.toId, link.label, link.kind)
    }
    return AgentTopologyGraph(displayNodes, parentRelations + linkRelations, root.id)
}

/**
 * [T-android-topology-peer-edges] One non-tree link carried by the runtime snapshot.
 *
 * `kind` is already mapped to the diagram's own vocabulary so the renderer never
 * has to know about runtime enums.
 */
internal data class RuntimeTopologyLink(
    val fromId: String,
    val toId: String,
    val kind: AgentTopologyEdgeKind,
    val label: String?,
)

/**
 * [T-android-topology-peer-edges] Read the runtime snapshot's non-tree links.
 *
 * Sources (see `SessionTreeRuntime`):
 *  - `topologyEdges[]` — `{fromNodeId, toNodeId, kind: PARENT_CHILD|SUBSCRIPTION|
 *    TEAM_PEER, revokedAtMillis}`. A missing `revokedAtMillis` means still active.
 *    PARENT_CHILD is dropped here so the caller can derive delegation lines from
 *    `parentId` and avoid double-stroking.
 *  - `subscriptions[]` — the traditional publisher/subscriber link. The arrow
 *    points publisher → subscriber, i.e. the direction notifications travel.
 *
 * A malformed or absent array yields an empty list rather than throwing: a
 * diagram must still render when the runtime JSON is partial.
 */
internal fun parseRuntimeTopologyLinks(runtimeJson: String): List<RuntimeTopologyLink> =
    runCatching {
        val root = JSONObject(runtimeJson)
        buildList {
            val edges = root.optJSONArray("topologyEdges") ?: JSONArray()
            for (index in 0 until edges.length()) {
                val json = edges.optJSONObject(index) ?: continue
                val from = json.optString("fromNodeId").takeIf(String::isNotBlank) ?: continue
                val to = json.optString("toNodeId").takeIf(String::isNotBlank) ?: continue
                if (from == to) continue
                // Absent key == never revoked == active.
                val revoked = json.optLong("revokedAtMillis", 0L)
                if (revoked != 0L) continue
                val kind = when (json.optString("kind")) {
                    "TEAM_PEER" -> AgentTopologyEdgeKind.PEER
                    "SUBSCRIPTION" -> AgentTopologyEdgeKind.SUBSCRIPTION
                    else -> AgentTopologyEdgeKind.PARENT
                }
                add(RuntimeTopologyLink(from, to, kind, json.optString("kind").lowercase()))
            }
            val subscriptions = root.optJSONArray("subscriptions") ?: JSONArray()
            for (index in 0 until subscriptions.length()) {
                val json = subscriptions.optJSONObject(index) ?: continue
                val publisher = json.optString("publisherNodeId").takeIf(String::isNotBlank) ?: continue
                val subscriber = json.optString("subscriberNodeId").takeIf(String::isNotBlank) ?: continue
                if (publisher == subscriber) continue
                if (json.optLong("revokedAtMillis", 0L) != 0L) continue
                add(RuntimeTopologyLink(publisher, subscriber, AgentTopologyEdgeKind.SUBSCRIPTION, "subscription"))
            }
        }
    }.getOrDefault(emptyList())

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
        val runtimeJson = withContext(Dispatchers.IO) {
            runCatching { RuntimeTreeStore.open(context.applicationContext).snapshot().toJson() }.getOrDefault("{}")
        }
        val topologyIds = runCatching {
            val nodes = JSONObject(runtimeJson).optJSONArray("nodes") ?: JSONArray()
            buildSet {
                for (i in 0 until nodes.length()) nodes.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.getOrDefault(setOf(sessionId))
        // [T-android-topology-run-root-keys] Node ids are NOT conversation ids once a
        // session has re-run: the tree roots run N>1 at "<sessionId>#run-<N>" while
        // messages/token rows stay under the plain id. Every DB lookup below goes
        // through the canonical id, and the results are re-keyed onto node ids for
        // the graph builder (which indexes by node). See remapRuntimeNodeKeys.
        val canonicalByNode = topologyIds.associateWith {
            com.openminis.app.data.repository.canonicalRuntimeSessionId(it)
        }
        val canonicalIds = canonicalByNode.values.toSet()
        val firstMessages = withContext(Dispatchers.IO) {
            runCatching { chatRepository.firstMessagesForSessions(canonicalIds.toList()) }
                .getOrDefault(emptyMap())
        }.let { remapRuntimeNodeKeys(topologyIds, it) }
        val sessionTitles = withContext(Dispatchers.IO) {
            buildMap {
                canonicalIds.filter { it != sessionId }.forEach { id ->
                    chatRepository.getSession(id)?.title?.takeIf { it.isNotBlank() }?.let { put(id, it) }
                }
            }
        }.let { remapRuntimeNodeKeys(topologyIds, it) }
        chatRepository.observeTokenUsagesForSessions(canonicalIds.toList()).collect { rows ->
            val canonicalTotals = aggregateAgentTopologyTokens(canonicalIds, rows)
            val totals = remapRuntimeNodeKeys(topologyIds, canonicalTotals)
            graph = agentTopologyGraphFromRuntimeJson(
                runtimeJson = runtimeJson,
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                currentSummary = currentSummary,
                // The root node may be "<sessionId>#run-N"; totals are re-keyed onto
                // node ids above, so ask for the node that is actually the root.
                currentTokens = totals[sessionId] ?: canonicalTotals[sessionId] ?: 0L,
                sessionTitles = sessionTitles,
                tokenTotalsBySession = totals,
                cardStrings = topologyCardStrings(context),
                firstMessagesBySession = firstMessages,
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
    val cardStrings = remember(context) { topologyCardStrings(context) }
    var zoom by remember { mutableStateOf(prefs.loadZoom()) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // [T-android-topology-export-options] Remembered, not transient: the
    // requirement asks for a default the user picks once.
    var transparent by remember { mutableStateOf(prefs.loadExportTransparent()) }
    // [T-android-topology-export-quality] The requirement asks the user to CHOOSE
    // the fineness, so the dialog needs a selection, not just a stated default.
    var exportQuality by remember { mutableStateOf(prefs.loadExportQuality()) }
    var showExportOptions by remember { mutableStateOf(false) }
    val layout = remember(nodes, relations) { layoutAgentTopology(nodes, relations) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(renderAgentTopologyPng(layout, AgentTopologyExportOptions(
                        transparentBackground = transparent,
                        cardStrings = cardStrings,
                        dpi = exportQuality.dpi,
                        maxDimensionPx = exportQuality.maxDimensionPx,
                        maxPixels = exportQuality.maxPixels,
                    )))
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
                // [T-android-topology-peer-edges] Peer and subscription links are
                // drawn here too (dashed, differently coloured) — previously only
                // parent/child lines reached the screen at all, so a Team's P2P
                // structure was invisible in the very view meant to show it.
                layout.edges.forEach { edge ->
                    if (edge.points.size >= 2) {
                        val edgeStyle = topologyEdgeStyle(edge.relation.kind)
                        val points = edge.points
                        for (index in 0 until points.lastIndex) {
                            drawLine(
                                color = Color(edgeStyle.argb),
                                start = Offset(points[index].x, points[index].y),
                                end = Offset(points[index + 1].x, points[index + 1].y),
                                strokeWidth = edgeStyle.strokeWidth,
                                pathEffect = edgeStyle.dashIntervals
                                    ?.let { PathEffect.dashPathEffect(it, 0f) },
                            )
                        }
                        // [T-android-topology-arrow-parity] Same shared geometry as
                        // the PNG exporter. Without this the screen showed no edge
                        // direction at all, so A->B and B->A looked identical here
                        // while the exported image distinguished them.
                        topologyArrowheadSegments(points).forEach { (tip, barb) ->
                            drawLine(
                                color = Color(edgeStyle.argb),
                                start = Offset(tip.x, tip.y),
                                end = Offset(barb.x, barb.y),
                                strokeWidth = edgeStyle.strokeWidth,
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
                    // [T-android-topology-card-layout] The SAME card lines the PNG
                    // export draws. This is the renderer the user actually sees,
                    // and it used to omit creation time and global order entirely —
                    // so those two required lines existed only in the exported
                    // file. Both renderers now consume one builder.
                    val card = topologyCardLines(item.node, item.tokenLabel, cardStrings)
                    val dividerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(210, 216, 226)
                        strokeWidth = 1f
                    }
                    var textY = item.rect.top + 20f
                    native.drawText(card.title, item.rect.left + 10f, textY, titlePaint)
                    textY += 18f
                    native.drawText(card.created, item.rect.left + 10f, textY, bodyPaint)
                    textY += 15f
                    native.drawText(card.order, item.rect.left + 10f, textY, bodyPaint)
                    textY += 15f
                    native.drawLine(
                        item.rect.left + 10f,
                        textY - 10f,
                        item.rect.right - 10f,
                        textY - 10f,
                        dividerPaint,
                    )
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
            // [T-android-topology-peer-edges] Legend.
            //
            // EDITORIAL CHOICE, not requirement text: the request describes what
            // the graph must SHOW (P2P links between sub-agents, request.md:33) but
            // never asks for a legend. Once the diagram draws three line styles,
            // however, a reader who has not seen this file cannot tell a delegation
            // edge from a peer edge — so "drawn" would not become "understandable",
            // which is the point of the requirement. Only rendered when a non-tree
            // link actually exists, so the common tree-only view is unchanged.
            val legendKinds = remember(relations) {
                relations.map { it.kind }.filter { it != AgentTopologyEdgeKind.PARENT }.distinct()
            }
            if (legendKinds.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (listOf(AgentTopologyEdgeKind.PARENT) + legendKinds).forEach { kind ->
                        val style = topologyEdgeStyle(kind)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.width(26.dp).height(10.dp)) {
                                drawLine(
                                    color = Color(style.argb),
                                    start = Offset(0f, size.height / 2f),
                                    end = Offset(size.width, size.height / 2f),
                                    strokeWidth = style.strokeWidth,
                                    pathEffect = style.dashIntervals
                                        ?.let { PathEffect.dashPathEffect(it, 0f) },
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    when (kind) {
                                        AgentTopologyEdgeKind.PARENT -> R.string.agent_topology_legend_parent
                                        AgentTopologyEdgeKind.PEER -> R.string.agent_topology_legend_peer
                                        AgentTopologyEdgeKind.SUBSCRIPTION ->
                                            R.string.agent_topology_legend_subscription
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-topology-export-options] Zoom is PERSISTED, so a user
                // who zoomed far out and closed the canvas had no way back to the
                // default scale — the string for this button existed and was
                // referenced nowhere, i.e. the affordance was designed and never
                // wired. The background chip that used to live here moved into the
                // export dialog, where the requirement puts that choice.
                androidx.compose.material3.TextButton(onClick = {
                    zoom = prefs.saveZoom(AgentTopologyPrefs.DEFAULT_GLOBAL_ZOOM)
                }) {
                    Text(stringResource(R.string.agent_topology_zoom_reset))
                }
                FloatingActionButton(onClick = { showExportOptions = true }) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.agent_topology_export))
                }
            }
            // [T-android-topology-export-options] The requirement: tapping export
            // must first offer the quality/background choice, with poster grade as
            // the default ("导出用这个图标，然后点击以后，可以选择默认的一个…精细度…
            // 默认的话就是达到一个海报级别的质感…导出的话，可以选择带上背景或者纯透明").
            // It used to jump straight into the system file picker, so the only
            // control was a chip whose state was lost on every reopen.
            //
            // The wording below reuses the strings that already existed for this
            // dialog and had zero references anywhere — i.e. the UI they were
            // written for was never built.
            if (showExportOptions) {
                AlertDialog(
                    onDismissRequest = { showExportOptions = false },
                    title = { Text(stringResource(R.string.agent_topology_export_png)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // [T-android-topology-export-quality] Selectable fineness.
                            // Previously this block only STATED the default
                            // ("agent_topology_export_poster_default" + _description",
                            // whose text used to claim 300 DPI while the code exported
                            // 350) — the requirement's "可以选择" had no control.
                            Text(
                                text = stringResource(R.string.agent_topology_export_quality_label),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            AgentTopologyExportQuality.entries.forEach { quality ->
                                val label = when (quality) {
                                    AgentTopologyExportQuality.STANDARD ->
                                        R.string.agent_topology_export_quality_standard
                                    AgentTopologyExportQuality.POSTER ->
                                        R.string.agent_topology_export_quality_poster
                                }
                                val description = when (quality) {
                                    AgentTopologyExportQuality.STANDARD ->
                                        R.string.agent_topology_export_quality_standard_description
                                    AgentTopologyExportQuality.POSTER ->
                                        R.string.agent_topology_export_quality_poster_description
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            exportQuality = prefs.saveExportQuality(quality)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = quality == exportQuality,
                                        onClick = { exportQuality = prefs.saveExportQuality(quality) },
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(label),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource(description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.agent_topology_export_background),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.FilterChip(
                                    selected = transparent,
                                    onClick = { transparent = true; prefs.saveExportTransparent(true) },
                                    label = { Text(stringResource(R.string.agent_topology_export_transparent)) },
                                )
                                androidx.compose.material3.FilterChip(
                                    selected = !transparent,
                                    onClick = { transparent = false; prefs.saveExportTransparent(false) },
                                    label = { Text(stringResource(R.string.agent_topology_export_solid)) },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showExportOptions = false
                            exportLauncher.launch("agent-topology.png")
                        }) {
                            Text(stringResource(R.string.agent_topology_export_png))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showExportOptions = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }
}
