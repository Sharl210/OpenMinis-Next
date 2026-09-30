package com.openminis.app.data

import com.openminis.app.data.repository.SessionSubtreeDeletionPlan
import kotlinx.coroutines.CancellationException

/**
 * Runs the artifact/row cleanup for a whole session subtree, and keeps
 * [SessionDeletionRetryStore] accurate about what is still owed.
 *
 * Why a pipeline instead of a loop at the call site: three user entry points
 * (delete one, delete a selection, delete a group with its members) all have to
 * end up here, and the retry ledger is the first thing a recursive delete
 * breaks. The chat and runtime-tree steps are inherently batched (one atomic
 * transaction for the whole subtree) while the artifact steps are per session,
 * so the bookkeeping is not something a call site can get right inline. It lives
 * here and is exercised on the JVM.
 *
 * ### Ledger contract
 *
 * The ledger is written **before** any step runs, and every step is removed from
 * it only once it has actually succeeded. Writing it after the fact is what
 * makes a crash indistinguishable from a clean finish: an id whose artifact
 * steps never started would look exactly like an id whose artifacts were
 * cleaned, and the next attempt would skip it. Two consequences:
 *
 *  - A session that owes every step is stored as owing every step, so an
 *    interrupted run leaves an accurate debt instead of nothing.
 *  - The per-step decision is made **per session id**, never per run. A batch
 *    step failing for one id must not gate the artifact steps of another.
 *
 * ### Retry reachability
 *
 * A retry must be able to find the ids it owes. Rebuilding the delete set from
 * the chat database cannot do that: the batched `chat` step already removed
 * those rows, so an id whose media cleanup failed would drop out of the plan and
 * its ledger row would sit there forever. The pipeline therefore unions
 * [SessionDeletionRetryStore.ids] into the worked set, which makes the ledger —
 * not the surviving rows — the authority on what is still owed.
 *
 * That union is not only bookkeeping. The carried-over ids have to reach the
 * plan the batch steps are *handed*, because a successful batch step is cleared
 * for every id in the run at once (one transaction covered the whole subtree).
 * A carried-over id kept out of `sessionIds` would have its `chat` row skipped —
 * `chat` deletes `sessionIds` — while the same run cleared its debt: an orphan
 * reported as a success, which is the one outcome this feature exists to
 * prevent. So the rule is: **the set handed to the batch steps and the set they
 * are cleared for is one and the same value** (`sessionIds` below), never two
 * sets that have to be kept in step by hand.
 */
internal class SessionSubtreeDeletionPipeline(
    private val retryStore: SessionDeletionRetryStore,
    private val batchSteps: List<BatchStep>,
    private val perSessionSteps: List<PerSessionStep>,
) {
    /** One step that has to see the whole subtree at once (its own atomicity). */
    data class BatchStep(val name: String, val action: suspend (SessionSubtreeDeletionPlan) -> Unit)

    /** One step that cleans a single session's artifacts. Must be idempotent. */
    data class PerSessionStep(val name: String, val action: suspend (String) -> Unit)

    data class Outcome(
        /** Every session id this run worked on, including retried ledger ids. */
        val sessionIds: List<String>,
        val outstanding: Map<String, Set<String>>,
    ) {
        val success: Boolean get() = outstanding.values.all { it.isEmpty() }
    }

    private val allStepNames: Set<String> =
        (batchSteps.map { it.name } + perSessionSteps.map { it.name }).toSet()

    suspend fun run(plan: SessionSubtreeDeletionPlan): Outcome {
        // Ids carried over from earlier attempts still owe work, even though the
        // plan built from the database can no longer see them. A row that names
        // *only* steps this build no longer has is not a retry at all: treating it
        // as one would pin the session at "nothing left to do" forever, so such an
        // id is ignored here and re-recorded as owing everything below.
        val carriedOver = retryStore.ids()
            .filter { id -> retryStore.load(id).any { it in allStepNames } }
            .toSet()
        val sessionIds = (plan.sessionIds + carriedOver).distinct()
        val outstanding = LinkedHashMap<String, MutableSet<String>>(sessionIds.size)
        sessionIds.forEach { id ->
            // An id the ledger knows is a retry and owes exactly what is recorded;
            // anything else is a fresh delete and owes every step. Recording the
            // full debt up front is what keeps an interrupted run honest — the
            // difference between "never started" and "already cleaned".
            val previous = retryStore.load(id).filter { it in allStepNames }
            outstanding[id] = LinkedHashSet(if (id in carriedOver) previous else allStepNames)
        }
        // Durable before the first step runs: an interruption from here on leaves
        // an accurate debt rather than a row that looks finished.
        persist(outstanding)
        // The runtime-tree step re-resolves from these roots, so a carried-over id
        // (whose chat row is already gone) still gets its nodes purged. The batch
        // steps are handed `sessionIds` — the same value the run clears them for
        // once they succeed — so both fields are widened here; widening the roots
        // alone left a carried-over id's `chat` row undeleted while its debt was
        // cleared, which reported a missed delete as a success.
        val roots = (plan.rootSessionIds + carriedOver).distinct()
        val workingPlan = plan.copy(sessionIds = sessionIds, rootSessionIds = roots)

        for (step in batchSteps) {
            val owedBy = sessionIds.filter { step.name in outstanding.getValue(it) }
            if (owedBy.isEmpty()) continue
            val failed = try {
                step.action(workingPlan)
                false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "subtree delete step '${step.name}' failed: ${error.message}")
                true
            }
            if (!failed) {
                // A batched step is all-or-nothing: one transaction covered the
                // whole subtree, so one success clears it for every id.
                outstanding.values.forEach { owed -> owed -= step.name }
            }
            persist(outstanding)
        }

        for (id in sessionIds) {
            val owed = outstanding.getValue(id)
            val steps = perSessionSteps.filter { it.name in owed }
            if (steps.isEmpty()) continue
            // Reuses the existing per-step runner for the sequential half: it
            // already owns "one step's failure does not abort the others" and the
            // CancellationException pass-through, so only the batching and the
            // ledger bookkeeping are new here.
            val result = SessionArtifactDeletionCoordinator(
                steps.map { step ->
                    SessionArtifactDeletionCoordinator.Step(step.name) {
                        step.action(id)
                        // One step, one durable verdict. A run that is cancelled
                        // after this point must not re-do work that is already
                        // done — nor lose the fact that it was done.
                        owed -= step.name
                        retryStore.save(id, owed)
                    }
                },
            ).run()
            result.failed.forEach { (name, error) ->
                android.util.Log.w(TAG, "session cleanup step '$name' failed for $id: ${error.message}")
                owed += name
                retryStore.save(id, owed)
            }
        }
        return Outcome(sessionIds, outstanding.mapValues { it.value.toSet() })
    }

    /** Writes the current debt for every id; an empty set removes the row. */
    private fun persist(outstanding: Map<String, MutableSet<String>>) {
        outstanding.forEach { (id, owed) -> retryStore.save(id, owed) }
    }

    private companion object {
        const val TAG = "SessionSubtreeDelete"
    }
}
