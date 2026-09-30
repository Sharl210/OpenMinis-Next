package com.openminis.app.browser

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID

/**
 * Tracks browser history with 7-day retention and persistent bookmarks.
 *
 * The on-disk format is a JSON object containing `history` and `bookmarks`.
 * Older releases wrote the history as a top-level JSON array; that shape is
 * still accepted when loading so upgrading does not discard browsing history.
 *
 * ## Failure visibility
 *
 * Every mutation that is supposed to survive a restart reports whether it
 * actually reached disk ([WriteOutcome]), and a mutation that could not be
 * written is **rolled back in memory** before the call returns. The store
 * therefore keeps a single truth: after any call, what is in memory is exactly
 * what a restart would load. Callers cannot be told "saved" about a change that
 * only ever existed in RAM, which is how a bookmark star could turn yellow,
 * survive until the next launch, and then be gone.
 *
 * Reads are equally explicit: [loadStatus] distinguishes "nothing has been
 * saved yet" from "something was there but could not be read", so an empty
 * list is never silently presented as the truth when the file was unreadable.
 */
class BrowserHistoryStore internal constructor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserHistory"
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val FILENAME = "browser_history.json"
        private const val TMP_FILENAME = "$FILENAME.tmp"
        private const val BACKUP_FILENAME = "$FILENAME.bak"

        /**
         * Where an unreadable main file is parked so that the next save cannot
         * destroy bytes the user might still be able to salvage.
         */
        private const val QUARANTINE_FILENAME = "$FILENAME.corrupt"

        @Volatile
        private var instance: BrowserHistoryStore? = null

        fun getInstance(context: Context): BrowserHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: BrowserHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val timestamp: Long = System.currentTimeMillis(),
        val domain: String = extractDomain(url),
    )

    data class Bookmark(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val timestamp: Long = System.currentTimeMillis(),
        val domain: String = extractDomain(url),
    )

    /** How the persisted state was read when this store was created. */
    enum class LoadOutcome {
        /** The primary file was read successfully. */
        LOADED,

        /**
         * No state file exists yet. The empty store is the truth, not a
         * failure — nothing has ever been saved.
         */
        EMPTY,

        /**
         * The primary file was missing or unusable and the state came from the
         * interrupted-write artifacts (`.tmp` / `.bak`) instead.
         */
        RECOVERED,

        /**
         * Something was on disk but nothing could be parsed. The in-memory
         * lists are empty and do **not** describe what is on disk: callers must
         * report a read failure rather than an empty history/bookmark list.
         */
        FAILED,
    }

    /** Result of [loadStatus]: how the on-disk state was obtained. */
    data class LoadStatus(
        val outcome: LoadOutcome,
        /** File the state was read from (or attempted from), when there was one. */
        val source: String? = null,
        /** Why the read failed, when it did. */
        val error: String? = null,
        /**
         * Name of the copy kept aside when the only file on disk was
         * unreadable, so callers can tell the user where the bytes went.
         */
        val preservedCopy: String? = null,
    ) {
        /** True when the in-memory lists are not what disk says. */
        val failed: Boolean get() = outcome == LoadOutcome.FAILED
    }

    /**
     * Outcome of a mutation that is supposed to be durable.
     *
     * [persisted] is `false` only when a change was attempted, could not be
     * written, and was rolled back: the store is then unchanged both in memory
     * and on disk, and the caller must **not** report the operation as done.
     * A call that had nothing to change (no match, invalid URL, duplicate
     * visit) reports `persisted = true` together with a value describing the
     * absence of a change.
     */
    data class WriteOutcome<out T>(
        val value: T,
        val persisted: Boolean,
        val error: String? = null,
    ) {
        val failed: Boolean get() = !persisted
    }

    private val entries = mutableListOf<Entry>()
    private val bookmarks = mutableListOf<Bookmark>()

    val loadStatus: LoadStatus

    init {
        loadStatus = load()
    }

    @Synchronized
    fun record(url: String, title: String): WriteOutcome<Boolean> {
        val normalized = BrowserUrlNormalizer.normalize(url)
        if (normalized.isEmpty()) return WriteOutcome(value = false, persisted = true)
        // Deduplicate consecutive visits to same canonical URL.
        if (entries.lastOrNull()?.url == normalized) return WriteOutcome(value = false, persisted = true)

        val snapshot = entries.toList()
        entries.add(Entry(url = normalized, title = title))
        pruneOld()
        val error = commit { entries.clear(); entries.addAll(snapshot) }
        return if (error != null) {
            WriteOutcome(value = false, persisted = false, error = error)
        } else {
            WriteOutcome(value = true, persisted = true)
        }
    }

    @Synchronized
    fun getEntries(): List<Entry> = entries.sortedByDescending { it.timestamp }

    @Synchronized
    fun search(query: String): List<Entry> {
        if (query.isBlank()) return getEntries()
        val q = query.lowercase()
        return entries.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun groupedByDay(): Map<String, List<Entry>> {
        val cal = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

        return getEntries().groupBy { entry ->
            cal.timeInMillis = entry.timestamp
            when {
                cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
                cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> {
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    "$month/$day"
                }
            }
        }
    }

    /** Get unique domains from history (for cookie domain listing). */
    @Synchronized
    fun uniqueDomains(): List<String> {
        return entries.map { it.domain }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** Delete one history entry by id. */
    @Synchronized
    fun deleteHistory(id: String): WriteOutcome<Boolean> {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return WriteOutcome(value = false, persisted = true)
        val removed = entries.removeAt(index)
        val error = commit { entries.add(index, removed) }
        return if (error != null) {
            WriteOutcome(value = false, persisted = false, error = error)
        } else {
            WriteOutcome(value = true, persisted = true)
        }
    }

    /** Delete history entries matching a URL. */
    @Synchronized
    fun deleteHistoryForUrl(url: String): WriteOutcome<Boolean> {
        val normalized = BrowserUrlNormalizer.normalize(url)
        val snapshot = entries.toList()
        entries.removeAll { it.url == normalized }
        if (entries.size == snapshot.size) return WriteOutcome(value = false, persisted = true)
        val error = commit { entries.clear(); entries.addAll(snapshot) }
        return if (error != null) {
            WriteOutcome(value = false, persisted = false, error = error)
        } else {
            WriteOutcome(value = true, persisted = true)
        }
    }

    @Synchronized
    fun clear(): WriteOutcome<Int> {
        val snapshot = entries.toList()
        if (snapshot.isEmpty()) return WriteOutcome(value = 0, persisted = true)
        entries.clear()
        val error = commit { entries.addAll(snapshot) }
        return if (error != null) {
            WriteOutcome(value = 0, persisted = false, error = error)
        } else {
            WriteOutcome(value = snapshot.size, persisted = true)
        }
    }

    // -- Persistent bookmarks -------------------------------------------------

    @Synchronized
    fun getBookmarks(): List<Bookmark> = bookmarks.sortedByDescending { it.timestamp }

    @Synchronized
    fun searchBookmarks(query: String): List<Bookmark> {
        if (query.isBlank()) return getBookmarks()
        val q = query.lowercase()
        return bookmarks.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun isBookmarked(url: String): Boolean {
        val normalized = BrowserUrlNormalizer.normalize(url)
        return normalized.isNotBlank() && bookmarks.any { it.url == normalized }
    }

    @Synchronized
    fun findBookmark(idOrUrl: String?): Bookmark? {
        val value = idOrUrl?.trim().orEmpty()
        if (value.isEmpty()) return null
        val normalized = BrowserUrlNormalizer.normalize(value)
        return bookmarks.firstOrNull { it.id == value || it.url == normalized }
    }

    /** Create a bookmark, or update its title when the URL is already saved. */
    @Synchronized
    fun addBookmark(url: String, title: String): WriteOutcome<Bookmark?> {
        val normalized = BrowserUrlNormalizer.normalize(url)
        if (normalized.isEmpty()) {
            return WriteOutcome(value = null, persisted = true, error = "invalid bookmark URL")
        }
        val existingIndex = bookmarks.indexOfFirst { it.url == normalized }
        if (existingIndex >= 0) {
            val existing = bookmarks[existingIndex]
            val updated = if (title.isNotBlank() && title != existing.title) {
                existing.copy(title = title)
            } else {
                existing
            }
            if (updated == existing) return WriteOutcome(value = existing, persisted = true)
            bookmarks[existingIndex] = updated
            val error = commit { bookmarks[existingIndex] = existing }
            return if (error != null) {
                WriteOutcome(value = existing, persisted = false, error = error)
            } else {
                WriteOutcome(value = updated, persisted = true)
            }
        }

        val bookmark = Bookmark(url = normalized, title = title)
        bookmarks += bookmark
        val error = commit { bookmarks.remove(bookmark) }
        return if (error != null) {
            WriteOutcome(value = null, persisted = false, error = error)
        } else {
            WriteOutcome(value = bookmark, persisted = true)
        }
    }

    /**
     * Add when absent, remove when present.
     *
     * The returned value is the bookmarked state **after** the call: when the
     * write failed the change is rolled back, so the value still describes the
     * old state and the caller's star icon stays truthful.
     */
    @Synchronized
    fun toggleBookmark(url: String, title: String): WriteOutcome<Boolean> {
        val normalized = BrowserUrlNormalizer.normalize(url)
        if (normalized.isEmpty()) {
            return WriteOutcome(value = false, persisted = true, error = "invalid bookmark URL")
        }
        val existing = bookmarks.firstOrNull { it.url == normalized }
        if (existing == null) {
            val added = addBookmark(normalized, title)
            return WriteOutcome(
                value = added.value != null,
                persisted = added.persisted,
                error = added.error,
            )
        }
        val removed = bookmarks.remove(existing)
        if (!removed) return WriteOutcome(value = true, persisted = true)
        val error = commit { bookmarks.add(existing) }
        return if (error != null) {
            WriteOutcome(value = true, persisted = false, error = error)
        } else {
            WriteOutcome(value = false, persisted = true)
        }
    }

    /** Update an existing bookmark without changing its stable id. */
    @Synchronized
    fun updateBookmark(id: String, url: String? = null, title: String? = null): WriteOutcome<Bookmark?> {
        val index = bookmarks.indexOfFirst { it.id == id }
        if (index < 0) return WriteOutcome(value = null, persisted = true, error = "bookmark not found")
        val current = bookmarks[index]
        val nextUrl = url?.let(BrowserUrlNormalizer::normalize)?.takeIf { it.isNotEmpty() } ?: current.url
        val duplicate = bookmarks.any { it.id != id && it.url == nextUrl }
        if (duplicate) {
            return WriteOutcome(value = null, persisted = true, error = "duplicate bookmark URL")
        }
        val updated = current.copy(
            url = nextUrl,
            title = title ?: current.title,
            domain = extractDomain(nextUrl),
        )
        if (updated == current) return WriteOutcome(value = current, persisted = true)
        bookmarks[index] = updated
        val error = commit { bookmarks[index] = current }
        return if (error != null) {
            WriteOutcome(value = current, persisted = false, error = error)
        } else {
            WriteOutcome(value = updated, persisted = true)
        }
    }

    @Synchronized
    fun removeBookmark(id: String): WriteOutcome<Boolean> {
        val index = bookmarks.indexOfFirst { it.id == id }
        if (index < 0) return WriteOutcome(value = false, persisted = true)
        val removed = bookmarks.removeAt(index)
        val error = commit { bookmarks.add(index, removed) }
        return if (error != null) {
            WriteOutcome(value = false, persisted = false, error = error)
        } else {
            WriteOutcome(value = true, persisted = true)
        }
    }

    /** Remove a bookmark by URL; useful for the toolbar toggle. */
    @Synchronized
    fun removeBookmarkForUrl(url: String): WriteOutcome<Boolean> {
        val normalized = BrowserUrlNormalizer.normalize(url)
        val snapshot = bookmarks.toList()
        bookmarks.removeAll { it.url == normalized }
        if (bookmarks.size == snapshot.size) return WriteOutcome(value = false, persisted = true)
        val error = commit { bookmarks.clear(); bookmarks.addAll(snapshot) }
        return if (error != null) {
            WriteOutcome(value = false, persisted = false, error = error)
        } else {
            WriteOutcome(value = true, persisted = true)
        }
    }

    @Synchronized
    fun clearBookmarks(): WriteOutcome<Int> {
        val snapshot = bookmarks.toList()
        if (snapshot.isEmpty()) return WriteOutcome(value = 0, persisted = true)
        bookmarks.clear()
        val error = commit { bookmarks.addAll(snapshot) }
        return if (error != null) {
            WriteOutcome(value = 0, persisted = false, error = error)
        } else {
            WriteOutcome(value = snapshot.size, persisted = true)
        }
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoff }
    }

    /**
     * Write the current state, undoing [rollback] when the write fails.
     *
     * Keeping memory and disk equal after every call is what makes failures
     * observable: there is never a "saved in RAM only" state that the UI, an
     * agent tool, or a later reader has to remember to distrust. The rollback
     * itself is a local list restore and cannot fail.
     *
     * @return `null` on success, otherwise the write failure reason (the
     *   in-memory state has already been restored at that point).
     */
    private fun commit(rollback: () -> Unit): String? {
        val error = save()
        if (error != null) {
            rollback()
            Log.w(TAG, "Reverted an in-memory change that could not be persisted: $error")
        }
        return error
    }

    /**
     * Persist the current in-memory state.
     *
     * @return `null` when the state is durable, otherwise a human-readable
     *   reason. A failure is never swallowed: it is logged *and* returned so
     *   [commit] can roll the change back and the caller can tell the user.
     */
    private fun save(): String? {
        val dir = context.filesDir
        val file = File(dir, FILENAME)
        val tmp = File(dir, TMP_FILENAME)
        val backup = File(dir, BACKUP_FILENAME)
        return try {
            val root = JSONObject()
            root.put("history", JSONArray().also { array -> entries.forEach { array.put(entryJson(it)) } })
            root.put("bookmarks", JSONArray().also { array -> bookmarks.forEach { array.put(bookmarkJson(it)) } })
            val bytes = root.toString().toByteArray(Charsets.UTF_8)

            FileOutputStream(tmp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (file.exists()) {
                if (backup.exists() && !backup.delete()) {
                    Log.w(TAG, "Could not drop the previous backup ${backup.name}")
                }
                if (!file.renameTo(backup)) {
                    Log.w(TAG, "Failed to create history backup before replacement")
                }
            }
            if (!tmp.renameTo(file)) {
                // Keep the complete tmp for the next load attempt, then restore
                // the previous file if it was moved to backup.
                if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
                    Log.w(TAG, "Could not restore ${backup.name} after a failed replace")
                }
                "could not replace $FILENAME (temp rename failed)"
            } else {
                // The new file is now durable; stale backup is only a fallback for
                // interrupted writes and can be removed after successful replace.
                if (backup.exists() && !backup.delete()) {
                    Log.w(TAG, "Could not drop the stale backup ${backup.name}")
                }
                null
            }
        } catch (e: Exception) {
            if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
                Log.w(TAG, "Could not restore ${backup.name} after a failed save")
            }
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.w(TAG, "Failed to save history: $reason")
            "could not write $FILENAME: $reason"
        }
    }

    /**
     * Read the persisted state into memory.
     *
     * Never invents an empty history: when something was on disk but nothing
     * could be parsed, the outcome is [LoadOutcome.FAILED] with the reason, and
     * the unreadable file is set aside instead of being left in place for the
     * next save to overwrite.
     */
    private fun load(): LoadStatus {
        val dir = context.filesDir
        val file = File(dir, FILENAME)
        val tmp = File(dir, TMP_FILENAME)
        val backup = File(dir, BACKUP_FILENAME)

        val primaryError = if (file.exists()) loadFromFile(file) else null
        if (file.exists() && primaryError == null) {
            pruneAndPersist()
            return LoadStatus(LoadOutcome.LOADED, FILENAME)
        }

        // The main file is absent or unusable: an interrupted write usually
        // left a complete copy in tmp or in the pre-replacement backup.
        for ((candidate, name) in listOf(tmp to TMP_FILENAME, backup to BACKUP_FILENAME)) {
            if (!candidate.exists()) continue
            val error = loadFromFile(candidate)
            if (error != null) {
                Log.w(TAG, "Rejected $name while recovering: $error")
                continue
            }
            if (!candidate.renameTo(file)) {
                Log.w(TAG, "Read state from $name but could not promote it to $FILENAME")
            }
            pruneAndPersist()
            return LoadStatus(
                outcome = LoadOutcome.RECOVERED,
                source = name,
                error = primaryError ?: "no $FILENAME",
            )
        }

        entries.clear()
        bookmarks.clear()

        if (!file.exists()) return LoadStatus(LoadOutcome.EMPTY)

        val reason = primaryError ?: "unreadable $FILENAME"
        val preservedCopy = quarantine(file)
        Log.w(TAG, "History read failed ($reason); kept the unreadable file as ${preservedCopy ?: "?"}")
        return LoadStatus(
            outcome = LoadOutcome.FAILED,
            source = FILENAME,
            error = reason,
            preservedCopy = preservedCopy,
        )
    }

    /** Drop expired entries after a successful read and persist the shrink. */
    private fun pruneAndPersist() {
        val snapshot = entries.toList()
        val before = entries.size
        pruneOld()
        // A failed prune write rolls the prune back: the retention window is
        // bookkeeping, and the read itself still succeeded, so the load
        // outcome must not be turned into a failure by it.
        if (entries.size != before) commit { entries.clear(); entries.addAll(snapshot) }
    }

    /**
     * Move an unreadable state file aside so a later save cannot overwrite it.
     *
     * @return the name it was moved to, or `null` when it could not be moved.
     */
    private fun quarantine(file: File): String? {
        val target = File(file.parentFile, QUARANTINE_FILENAME)
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Could not clear the previous ${target.name}")
        }
        return if (file.renameTo(target)) {
            QUARANTINE_FILENAME
        } else {
            Log.w(TAG, "Could not set aside the unreadable $FILENAME")
            null
        }
    }

    /**
     * Load one complete JSON file, clearing partial in-memory state first.
     *
     * @return `null` when the file was parsed, otherwise the reason it was
     *   rejected. A rejected file leaves the in-memory lists untouched so the
     *   caller can try the next candidate, and the reason is not swallowed:
     *   [load] turns it into [loadStatus].
     */
    private fun loadFromFile(file: File): String? {
        return try {
            val text = FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) }
            val loadedEntries = mutableListOf<Entry>()
            val loadedBookmarks = mutableListOf<Bookmark>()
            if (text.trimStart().startsWith("[")) {
                parseHistoryArray(JSONArray(text), loadedEntries)
            } else {
                val root = JSONObject(text)
                root.optJSONArray("history")?.let { parseHistoryArray(it, loadedEntries) }
                root.optJSONArray("bookmarks")?.let { parseBookmarkArray(it, loadedBookmarks) }
            }
            entries.clear()
            entries.addAll(loadedEntries)
            bookmarks.clear()
            bookmarks.addAll(loadedBookmarks)
            null
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.w(TAG, "Rejected ${file.name}: $reason")
            reason
        }
    }

    private fun parseHistoryArray(array: JSONArray, target: MutableList<Entry>) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url", "")
            val url = BrowserUrlNormalizer.normalize(rawUrl)
            if (url.isEmpty()) continue
            target.add(
                Entry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    url = url,
                    title = obj.optString("title", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    domain = obj.optString("domain", extractDomain(url)),
                ),
            )
        }
    }

    private fun parseBookmarkArray(array: JSONArray, target: MutableList<Bookmark>) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url", "")
            val url = BrowserUrlNormalizer.normalize(rawUrl)
            if (url.isEmpty()) continue
            target.add(
                Bookmark(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    url = url,
                    title = obj.optString("title", ""),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    domain = obj.optString("domain", extractDomain(url)),
                ),
            )
        }
    }

    private fun entryJson(entry: Entry) = JSONObject().apply {
        put("id", entry.id)
        put("url", entry.url)
        put("title", entry.title)
        put("timestamp", entry.timestamp)
        put("domain", entry.domain)
    }

    private fun bookmarkJson(bookmark: Bookmark) = JSONObject().apply {
        put("id", bookmark.id)
        put("url", bookmark.url)
        put("title", bookmark.title)
        put("timestamp", bookmark.timestamp)
        put("domain", bookmark.domain)
    }
}

private fun extractDomain(url: String): String {
    return try {
        URI(url).host ?: ""
    } catch (_: Exception) {
        // Deliberate fallback, not a swallowed failure. The domain is display
        // and grouping sugar (cookie-domain listing, "title or domain" rows);
        // `java.net.URI` rejects plenty of URLs the WebView loads happily
        // (minis://, about:blank, raw spaces in the authority), and the empty
        // string is already the documented "unknown domain" value. There is no
        // caller action that an exception here would inform, and this runs
        // inside a data-class default argument where throwing would be worse.
        ""
    }
}
