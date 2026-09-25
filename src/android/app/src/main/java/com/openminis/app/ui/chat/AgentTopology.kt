package com.openminis.app.ui.chat

import java.util.ArrayDeque
import java.util.Locale
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
