package com.janreins.audiobook.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Library-only state in the existing private preferences file. */
class LibraryStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE)

    val libraryVersion: StateFlow<Long> = version.asStateFlow()

    fun isFinished(bookId: String): Boolean = prefs.getBoolean("finished_$bookId", false)

    fun setFinished(bookId: String, finished: Boolean) {
        prefs.edit().putBoolean("finished_$bookId", finished).apply()
        notifyProgressChanged()
    }

    fun getLastPlayedAt(bookId: String): Long? =
        if (prefs.contains("last_played_at_$bookId")) prefs.getLong("last_played_at_$bookId", 0L) else null

    fun setLastPlayedAt(bookId: String, timestamp: Long) {
        // No version bump: the library re-sorts on open or book change, not on every play/pause.
        prefs.edit().putLong("last_played_at_$bookId", timestamp).apply()
    }

    fun getSort(): LibrarySort = LibrarySort.entries.firstOrNull {
        it.name == prefs.getString("library_sort", null)
    } ?: LibrarySort.TITLE

    fun setSort(sort: LibrarySort) {
        prefs.edit().putString("library_sort", sort.name).apply()
    }

    fun getFilter(): LibraryFilter = LibraryFilter.entries.firstOrNull {
        it.name == prefs.getString("library_filter", null)
    } ?: LibraryFilter.ALL

    fun setFilter(filter: LibraryFilter) {
        prefs.edit().putString("library_filter", filter.name).apply()
    }

    /** New installs and updates open on the bookshelf; "Show as list" in the ⋮ menu switches back. */
    fun getView(): LibraryView = LibraryView.entries.firstOrNull {
        it.name == prefs.getString("library_view", null)
    } ?: LibraryView.SHELVES

    fun setView(view: LibraryView) {
        prefs.edit().putString("library_view", view.name).apply()
    }

    /** Asks the library to re-sort: explicit finished/unplayed changes and book changes only. */
    fun notifyProgressChanged() {
        version.value += 1L
    }

    companion object {
        // Shared by the ViewModel and player store instances; all writes happen on the main thread.
        private val version = MutableStateFlow(0L)
    }
}
