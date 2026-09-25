package com.openminis.app.ui.chat

import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

sealed interface ToolDependencyParseResult {
    data class Valid(val ids: Set<String>) : ToolDependencyParseResult
    data class Invalid(val reason: String) : ToolDependencyParseResult
}

/** Reads optional dependency metadata carried in tool args without mutating them. */
fun parseToolCallDependencies(args: JSONObject): ToolDependencyParseResult {
    val ids = linkedSetOf<String>()
    for (key in listOf("depends_on", "dependsOn")) {
        if (!args.has(key) || args.isNull(key)) continue
        when (val value = args.opt(key)) {
            is String -> {
                val id = value.trim()
                if (id.isEmpty()) return ToolDependencyParseResult.Invalid("$key must not be blank")
                ids += id
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val id = value.optString(index, "").trim()
                if (id.isEmpty()) return ToolDependencyParseResult.Invalid("$key entries must be non-blank tool call ids")
                ids += id
            }
            else -> return ToolDependencyParseResult.Invalid("$key must be a tool call id or array of ids")
        }
    }
    return ToolDependencyParseResult.Valid(ids)
}

/** One tool call and the IDs of calls that must finish successfully first. */
data class ToolBatchItem<T>(
    val id: String,
    val dependsOn: Set<String> = emptySet(),
    val value: T,
    val dependencyError: String? = null,
)

data class ToolBatchExecution<R>(
    val success: Boolean,
    val value: R? = null,
    val error: String? = null,
    val cancelled: Boolean = false,
)

enum class ToolBatchStatus { SUCCESS, FAILED, BLOCKED, CANCELLED }

data class ToolBatchOutcome<T, R>(
    val item: ToolBatchItem<T>,
    val status: ToolBatchStatus,
    val value: R? = null,
    val error: String? = null,
)

/**
 * Executes each ready dependency layer concurrently. Invalid/cyclic branches are
 * reported per call; unrelated branches continue. Results retain input order.
 */
object ToolCallBatchScheduler {
    suspend fun <T, R> execute(
        items: List<ToolBatchItem<T>>,
        operation: suspend (T) -> ToolBatchExecution<R>,
    ): List<ToolBatchOutcome<T, R>> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()

        val outcomes = arrayOfNulls<ToolBatchOutcome<T, R>>(items.size)
        val indicesById = items.indices.groupBy { items[it].id }
        val uniqueIndexById = indicesById.mapNotNull { (id, indices) ->
            indices.singleOrNull()?.let { id to it }
        }.toMap()

        indicesById.filterValues { it.size > 1 }.forEach { (id, indices) ->
            indices.forEach { index ->
                outcomes[index] = outcome(items[index], ToolBatchStatus.FAILED, "Duplicate tool call id: $id")
            }
        }

        uniqueIndexById.forEach { (_, index) ->
            val dependencyError = items[index].dependencyError
            if (dependencyError != null) {
                outcomes[index] = outcome(items[index], ToolBatchStatus.FAILED, dependencyError)
                return@forEach
            }
            val missing = items[index].dependsOn.firstOrNull { it !in indicesById }
            val ambiguous = items[index].dependsOn.firstOrNull { indicesById[it]?.size ?: 0 > 1 }
            when {
                missing != null -> outcomes[index] = outcome(
                    items[index], ToolBatchStatus.FAILED, "Unknown dependency tool call id: $missing",
                )
                ambiguous != null -> outcomes[index] = outcome(
                    items[index], ToolBatchStatus.FAILED, "Dependency tool call id is duplicated: $ambiguous",
                )
            }
        }

        val pending = uniqueIndexById.values.filterTo(linkedSetOf()) { outcomes[it] == null }
        while (pending.isNotEmpty()) {
            // A failed prerequisite blocks only its dependent branch.
            val blocked = pending.filter { index ->
                items[index].dependsOn.any { dependencyId ->
                    val dependencyIndex = uniqueIndexById[dependencyId]
                    dependencyIndex != null && outcomes[dependencyIndex]?.status?.let { it != ToolBatchStatus.SUCCESS } == true
                }
            }
            blocked.forEach { index ->
                val failedDependency = items[index].dependsOn.first { dependencyId ->
                    val dependencyIndex = uniqueIndexById[dependencyId]
                    dependencyIndex != null && outcomes[dependencyIndex]?.status?.let { it != ToolBatchStatus.SUCCESS } == true
                }
                outcomes[index] = outcome(
                    items[index], ToolBatchStatus.BLOCKED, "Dependency failed or was blocked: $failedDependency",
                )
            }
            pending.removeAll(blocked.toSet())
            if (pending.isEmpty()) break

            val ready = pending.filter { index ->
                items[index].dependsOn.all { dependencyId ->
                    uniqueIndexById[dependencyId]?.let { outcomes[it]?.status == ToolBatchStatus.SUCCESS } == true
                }
            }
            if (ready.isEmpty()) {
                val cyclic = cycleMembers(pending, items, uniqueIndexById)
                if (cyclic.isEmpty()) {
                    pending.forEach { index ->
                        outcomes[index] = outcome(items[index], ToolBatchStatus.BLOCKED, "Unresolved dependency chain")
                    }
                    pending.clear()
                } else {
                    cyclic.forEach { index ->
                        outcomes[index] = outcome(items[index], ToolBatchStatus.FAILED, "Cyclic tool-call dependency")
                    }
                    pending.removeAll(cyclic)
                }
                continue
            }

            val completed = ready.map { index ->
                async {
                    val item = items[index]
                    val result = try {
                        operation(item.value)
                    } catch (_: CancellationException) {
                        ToolBatchExecution<R>(
                            success = false,
                            error = "Tool call cancelled",
                            cancelled = true,
                        )
                    } catch (error: Throwable) {
                        ToolBatchExecution<R>(
                            success = false,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                    index to result
                }
            }.awaitAll()

            completed.forEach { (index, result) ->
                val status = when {
                    result.cancelled -> ToolBatchStatus.CANCELLED
                    result.success -> ToolBatchStatus.SUCCESS
                    else -> ToolBatchStatus.FAILED
                }
                outcomes[index] = ToolBatchOutcome(items[index], status, result.value, result.error)
            }
            pending.removeAll(ready.toSet())
        }

        outcomes.mapIndexed { index, result ->
            result ?: outcome(items[index], ToolBatchStatus.BLOCKED, "Tool call was not scheduled")
        }
    }

    private fun <T, R> outcome(
        item: ToolBatchItem<T>,
        status: ToolBatchStatus,
        error: String,
    ) = ToolBatchOutcome<T, R>(item, status, error = error)

    /** Finds actual strongly-connected cycle members, not their downstream nodes. */
    private fun <T> cycleMembers(
        pending: Set<Int>,
        items: List<ToolBatchItem<T>>,
        uniqueIndexById: Map<String, Int>,
    ): Set<Int> {
        var nextIndex = 0
        val indices = mutableMapOf<Int, Int>()
        val lowLinks = mutableMapOf<Int, Int>()
        val stack = ArrayDeque<Int>()
        val onStack = mutableSetOf<Int>()
        val cycles = mutableSetOf<Int>()

        fun visit(node: Int) {
            indices[node] = nextIndex
            lowLinks[node] = nextIndex
            nextIndex++
            stack.addLast(node)
            onStack += node

            items[node].dependsOn.mapNotNull(uniqueIndexById::get)
                .filter(pending::contains)
                .forEach { dependency ->
                    if (dependency !in indices) {
                        visit(dependency)
                        lowLinks[node] = minOf(lowLinks.getValue(node), lowLinks.getValue(dependency))
                    } else if (dependency in onStack) {
                        lowLinks[node] = minOf(lowLinks.getValue(node), indices.getValue(dependency))
                    }
                }

            if (lowLinks[node] == indices[node]) {
                val component = mutableListOf<Int>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                val selfCycle = component.size == 1 && items[component.single()].dependsOn.any { dependencyId ->
                    uniqueIndexById[dependencyId] == node
                }
                if (component.size > 1 || selfCycle) cycles += component
            }
        }

        pending.forEach { if (it !in indices) visit(it) }
        return cycles
    }
}
