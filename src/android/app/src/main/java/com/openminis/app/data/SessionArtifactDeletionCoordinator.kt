package com.openminis.app.data

import kotlinx.coroutines.CancellationException

/**
 * Runs session cleanup steps independently. Each step must be idempotent so a
 * caller can retry only failed steps without repeating destructive work.
 */
class SessionArtifactDeletionCoordinator(
    private val steps: List<Step>,
) {
    data class Step(val name: String, val action: suspend () -> Unit)
    data class Result(val completed: List<String>, val failed: Map<String, Throwable>) {
        val success: Boolean get() = failed.isEmpty()
    }

    suspend fun run(): Result {
        val completed = mutableListOf<String>()
        val failed = linkedMapOf<String, Throwable>()
        for (step in steps) {
            try {
                step.action()
                completed += step.name
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failed[step.name] = error
            }
        }
        return Result(completed, failed)
    }
}
