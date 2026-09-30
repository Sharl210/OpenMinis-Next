package com.openminis.app.feature.runtime

/**
 * Persistent adapter around the existing metadata directory contract.
 * Runtime authorization remains outside this slice; detail is only accepted
 * after the caller explicitly requests the second read.
 */
class RuntimeCommunicationRepository(
    private val store: RuntimeCommunicationFileStore,
    maxPageLimit: Int = RuntimeCommunicationDirectory.MAX_PAGE_LIMIT,
    maxResultBudgetChars: Int = RuntimeCommunicationDirectory.MAX_RESULT_BUDGET_CHARS,
) {
    private val directory = RuntimeCommunicationDirectory(maxPageLimit, maxResultBudgetChars)

    private val snapshotPageLimit = maxPageLimit
    private val snapshotResultBudgetChars = maxResultBudgetChars

    init {
        store.read().forEach(directory::append)
    }

    @Synchronized
    fun append(record: RuntimeCommunicationMetadata): Boolean {
        val inserted = directory.append(record)
        if (inserted) store.write(snapshot())
        return inserted
    }

    fun find(recordId: String): RuntimeCommunicationMetadata? =
        directory.find(recordId)

    @Synchronized
    fun query(query: RuntimeCommunicationQuery): RuntimeCommunicationQueryResult =
        directory.query(query)

    @Synchronized
    fun requestDetail(request: RuntimeCommunicationDetailRequest): RuntimeCommunicationDetailResult =
        directory.requestDetail(request)

    @Synchronized
    fun deleteForSession(sessionId: String): Int {
        if (sessionId.isBlank()) return 0
        val retained = directory.allRecords().filter { record ->
            record.sender.sessionId != sessionId && record.receiver.sessionId != sessionId
        }
        val removed = directory.size() - retained.size
        if (removed > 0) {
            directory.replaceAll(retained)
            store.write(snapshot())
        }
        return removed
    }

    private fun snapshot(): List<RuntimeCommunicationMetadata> {
        val all = mutableListOf<RuntimeCommunicationMetadata>()
        var cursor: RuntimeCommunicationCursor? = null
        do {
            val result = directory.query(
                RuntimeCommunicationQuery(
                    cursor = cursor,
                    limit = snapshotPageLimit,
                    resultBudgetChars = snapshotResultBudgetChars,
                ),
            ) as? RuntimeCommunicationQueryResult.Accepted ?: return all
            all += result.page.records
            cursor = result.page.nextCursor
        } while (cursor != null)
        return all
    }
}
