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
 */
class BrowserHistoryStore internal constructor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserHistory"
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val FILENAME = "browser_history.json"

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

    private val entries = mutableListOf<Entry>()
    private val bookmarks = mutableListOf<Bookmark>()

    init {
        load()
    }

    @Synchronized
    fun record(url: String, title: String) {
        val normalized = BrowserUrlNormalizer.normalize(url)
        if (normalized.isEmpty()) return
        // Deduplicate consecutive visits to same canonical URL.
        if (entries.lastOrNull()?.url == normalized) return

        entries.add(Entry(url = normalized, title = title))
        pruneOld()
        save()
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
    fun deleteHistory(id: String): Boolean {
        val removed = entries.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    /** Delete history entries matching a URL. */
    @Synchronized
    fun deleteHistoryForUrl(url: String): Boolean {
        val normalized = BrowserUrlNormalizer.normalize(url)
        val removed = entries.removeAll { it.url == normalized }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun clear() {
        entries.clear()
        save()
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
    fun addBookmark(url: String, title: String): Bookmark? {
        val normalized = BrowserUrlNormalizer.normalize(url)
        if (normalized.isEmpty()) return null
        val existingIndex = bookmarks.indexOfFirst { it.url == normalized }
        if (existingIndex >= 0) {
            val existing = bookmarks[existingIndex]
            val updated = if (title.isNotBlank() && title != existing.title) {
                existing.copy(title = title)
            } else {
                existing
            }
            bookmarks[existingIndex] = updated
            if (updated != existing) save()
            return updated
        }

        val bookmark = Bookmark(url = normalized, title = title)
        bookmarks += bookmark
        save()
        return bookmark
    }

    /** Add when absent, remove when present; returns the new bookmarked state. */
    @Synchronized
    fun toggleBookmark(url: String, title: String): Boolean {
        val normalized = BrowserUrlNormalizer.normalize(url)
        val existing = bookmarks.firstOrNull { it.url == normalized }
        return if (existing != null) {
            bookmarks.remove(existing)
            save()
            false
        } else {
            addBookmark(normalized, title) != null
        }
    }

    /** Update an existing bookmark without changing its stable id. */
    @Synchronized
    fun updateBookmark(id: String, url: String? = null, title: String? = null): Bookmark? {
        val index = bookmarks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = bookmarks[index]
        val nextUrl = url?.let(BrowserUrlNormalizer::normalize)?.takeIf { it.isNotEmpty() } ?: current.url
        val duplicate = bookmarks.any { it.id != id && it.url == nextUrl }
        if (duplicate) return null
        val updated = current.copy(
            url = nextUrl,
            title = title ?: current.title,
            domain = extractDomain(nextUrl),
        )
        bookmarks[index] = updated
        save()
        return updated
    }

    @Synchronized
    fun removeBookmark(id: String): Boolean {
        val removed = bookmarks.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    /** Remove a bookmark by URL; useful for the toolbar toggle. */
    @Synchronized
    fun removeBookmarkForUrl(url: String): Boolean {
        val normalized = BrowserUrlNormalizer.normalize(url)
        val removed = bookmarks.removeAll { it.url == normalized }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun clearBookmarks() {
        bookmarks.clear()
        save()
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoff }
    }

    private fun save() {
        val dir = context.filesDir
        val file = File(dir, FILENAME)
        val tmp = File(dir, "$FILENAME.tmp")
        val backup = File(dir, "$FILENAME.bak")
        try {
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
                if (backup.exists()) backup.delete()
                if (!file.renameTo(backup)) {
                    Log.w(TAG, "Failed to create history backup before replacement")
                }
            }
            if (!tmp.renameTo(file)) {
                // Keep the complete tmp for the next load attempt, then restore
                // the previous file if it was moved to backup.
                if (!file.exists() && backup.exists()) backup.renameTo(file)
                throw IllegalStateException("history temp rename failed")
            }
            // The new file is now durable; stale backup is only a fallback for
            // interrupted writes and can be removed after successful replace.
            if (backup.exists()) backup.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save history: ${e.message}")
            if (!file.exists() && backup.exists()) backup.renameTo(file)
        }
    }

    private fun load() {
        val dir = context.filesDir
        val file = File(dir, FILENAME)
        val tmp = File(dir, "$FILENAME.tmp")
        val backup = File(dir, "$FILENAME.bak")
        try {
            val loaded = when {
                file.exists() && loadFromFile(file) -> true
                tmp.exists() && loadFromFile(tmp) -> {
                    tmp.renameTo(file)
                    true
                }
                backup.exists() && loadFromFile(backup) -> {
                    backup.renameTo(file)
                    true
                }
                else -> false
            }
            if (!loaded) return
            val before = entries.size
            pruneOld()
            if (entries.size != before) save()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history: ${e.message}")
            entries.clear()
            bookmarks.clear()
        }
    }

    /** Load one complete JSON file, clearing partial in-memory state first. */
    private fun loadFromFile(file: File): Boolean {
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
            true
        } catch (_: Exception) {
            false
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
        ""
    }
}
