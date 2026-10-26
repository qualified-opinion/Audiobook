package com.janreins.audiobook.data

import android.content.Context
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.data.model.Bookmark
import org.json.JSONArray
import org.json.JSONObject

/**
 * Progress, finished flag and bookmarks of one book in a backup file. [title] and [fileNames] let
 * an import find the book again when its id differs (another phone, or the folder on an SD card).
 */
data class BackupBook(
    val id: String, val title: String?, val fileNames: List<String>, val progress: BookProgress?,
    val finished: Boolean, val lastPlayedAt: Long?, val bookmarks: List<Bookmark>
)

/** A user-saved file with everyone's listening data; written and read through the system file picker. */
data class ListeningBackup(val exportedAt: Long, val books: List<BackupBook>) {

    fun toJson(): String = JSONObject().put("format", FORMAT).put("version", 1).put("exportedAt", exportedAt)
        .put("books", JSONArray().apply {
            books.forEach { b -> put(JSONObject().put("id", b.id).put("title", b.title ?: JSONObject.NULL)
                .put("files", JSONArray(b.fileNames)).put("finished", b.finished)
                .put("lastPlayedAt", b.lastPlayedAt ?: JSONObject.NULL)
                .put("progress", b.progress?.let { JSONObject().put("trackIndex", it.trackIndex)
                    .put("positionMs", it.positionMs) } ?: JSONObject.NULL)
                .put("bookmarks", JSONArray().apply {
                    b.bookmarks.forEach { m -> put(JSONObject().put("id", m.id).put("trackIndex", m.trackIndex)
                        .put("positionMs", m.positionMs).put("title", m.title).put("createdAt", m.createdAt)) }
                })) }
        }).toString(2)

    companion object {
        const val FORMAT = "audiobook-listening-backup"

        /** Null when the text isn't a backup from this app (or from a newer, unknown version). */
        fun fromJson(json: String): ListeningBackup? = try {
            val o = JSONObject(json)
            require(o.getString("format") == FORMAT && o.getInt("version") == 1)
            val array = o.getJSONArray("books")
            ListeningBackup(o.optLong("exportedAt", 0L), (0 until array.length()).map { i ->
                val b = array.getJSONObject(i)
                val id = b.getString("id").also { require(it.isNotBlank()) }
                val files = b.optJSONArray("files")
                val marks = b.optJSONArray("bookmarks") ?: JSONArray()
                BackupBook(id, if (b.isNull("title")) null else b.getString("title"),
                    (0 until (files?.length() ?: 0)).map { files!!.getString(it) },
                    b.optJSONObject("progress")?.let {
                        BookProgress(it.getInt("trackIndex").coerceAtLeast(0), it.getLong("positionMs").coerceAtLeast(0L))
                    },
                    b.optBoolean("finished", false),
                    if (b.isNull("lastPlayedAt")) null else b.getLong("lastPlayedAt"),
                    (0 until marks.length()).map { j ->
                        val m = marks.getJSONObject(j)
                        Bookmark(m.getString("id"), id, m.getLong("positionMs").coerceAtLeast(0L),
                            m.optString("title", "Bookmark"), m.optLong("createdAt", 0L),
                            m.optInt("trackIndex", 0).coerceAtLeast(0))
                    })
            })
        } catch (_: Exception) { null }

        /** Same id first; otherwise the only book with the same title and the same file names. */
        fun match(entry: BackupBook, library: List<Audiobook>): Audiobook? =
            library.find { it.id == entry.id } ?: entry.title?.let { title ->
                library.filter { it.title == title && it.tracks.map { t -> t.fileName } == entry.fileNames }
                    .singleOrNull()
            }

        /**
         * The backup's position wins unless this phone already has one that was played more recently,
         * so restoring an old file never moves a book backwards by accident.
         */
        fun shouldReplaceProgress(entry: BackupBook, hasLocalProgress: Boolean, localLastPlayedAt: Long?): Boolean =
            entry.progress != null && (!hasLocalProgress || (entry.lastPlayedAt ?: 0L) > (localLastPlayedAt ?: 0L))
    }
}

/** Result of an import, for the message shown in the library. */
data class RestoreSummary(val restored: Int, val notInLibrary: Int)

/** Reads listening data out of, and back into, the private preferences file. */
class ListeningBackupStore(context: Context) {
    private val prefs = PreferencesManager(context)
    private val libraryState = LibraryStateStore(context)
    private val raw = context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE)

    fun export(library: List<Audiobook>, now: Long = System.currentTimeMillis()): ListeningBackup {
        val prefixes = listOf("pos_", "track_", "bookmarks_", "finished_", "last_played_at_")
        // Only book ids: per-file keys from before multi-file books are migrated on the next scan.
        val ids = raw.all.keys.mapNotNull { key -> prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } }
            .filter { it.startsWith("file:") || it.startsWith("folder:") }.toSortedSet()
        return ListeningBackup(now, ids.map { id ->
            val book = library.find { it.id == id }
            BackupBook(id, book?.title, book?.tracks?.map { it.fileName }.orEmpty(),
                if (prefs.hasBookProgress(id)) prefs.getBookProgress(id) else null,
                libraryState.isFinished(id), libraryState.getLastPlayedAt(id), prefs.getBookmarks(id))
        }.filter { it.progress != null || it.finished || it.bookmarks.isNotEmpty() })
    }

    /**
     * Books not in [library] are still saved under their old id, so they apply if that folder is chosen later.
     * [onProgressChanged] lets a loaded book jump to its restored position.
     */
    fun restore(backup: ListeningBackup, library: List<Audiobook>,
                onProgressChanged: (String, BookProgress) -> Unit = { _, _ -> }): RestoreSummary {
        var missing = 0
        backup.books.forEach { entry ->
            val book = ListeningBackup.match(entry, library)
            if (book == null) missing++
            val id = book?.id ?: entry.id
            if (ListeningBackup.shouldReplaceProgress(entry, prefs.hasBookProgress(id), libraryState.getLastPlayedAt(id))) {
                val lastTrack = (book?.trackCount ?: Int.MAX_VALUE) - 1
                val progress = entry.progress!!.let { it.copy(trackIndex = it.trackIndex.coerceAtMost(lastTrack)) }
                prefs.saveBookProgress(id, progress)
                raw.edit().putBoolean("finished_$id", entry.finished).apply()
                entry.lastPlayedAt?.let { libraryState.setLastPlayedAt(id, it) }
                onProgressChanged(id, progress)
            }
            val merged = (prefs.getBookmarks(id) + entry.bookmarks.map { it.copy(audiobookId = id) }).distinctBy { it.id }
            if (merged.isNotEmpty()) prefs.saveBookmarks(id, merged)
        }
        libraryState.notifyProgressChanged()
        return RestoreSummary(backup.books.size - missing, missing)
    }
}
