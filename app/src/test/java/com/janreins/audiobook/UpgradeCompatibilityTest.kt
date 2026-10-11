package com.janreins.audiobook

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.BookGrouping
import com.janreins.audiobook.data.LibraryFilter
import com.janreins.audiobook.data.LibrarySort
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.data.model.ScannedAudioFile
import com.janreins.audiobook.player.CompletionRules
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeCompatibilityTest {
    private lateinit var context: Context
    private val bookId = "folder:primary:Books/Dune"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun preCoverPreferencesLoadWithoutMigrationOrChanges() {
        // These are the raw names and JSON fields stored before cover support.
        val raw = context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE)
        raw.edit()
            .putLong("pos_$bookId", 45678L)
            .putInt("track_$bookId", 2)
            .putString("key_last_played_book_id", bookId)
            .putBoolean("finished_$bookId", true)
            .putLong("last_played_at_$bookId", 123456789L)
            .putString("library_sort", "RECENT")
            .putString("library_filter", "FINISHED")
            .putString("bookmarks_$bookId", """[
                {"id":"bm1","audiobookId":"folder:primary:Books/Dune","positionMs":12000,
                 "trackIndex":1,"title":"Note","createdAt":987654321},
                {"id":"legacy","audiobookId":"folder:primary:Books/Dune","positionMs":100,
                 "title":"Before tracks","createdAt":123}
            ]""")
            .commit()
        val before = raw.all.toMap()
        val preferences = PreferencesManager(context)
        val library = LibraryStateStore(context)
        assertEquals(BookProgress(2, 45678L), preferences.getBookProgress(bookId))
        assertEquals(45678L, preferences.getPlaybackPosition(bookId))
        assertEquals(bookId, preferences.getLastPlayedBookId())
        assertTrue(preferences.hasBookProgress(bookId))
        assertTrue(library.isFinished(bookId))
        assertEquals(123456789L, library.getLastPlayedAt(bookId))
        assertEquals(LibrarySort.RECENT, library.getSort())
        assertEquals(LibraryFilter.FINISHED, library.getFilter())
        assertEquals(listOf(
            Bookmark("legacy", bookId, 100, "Before tracks", 123, 0),
            Bookmark("bm1", bookId, 12000, "Note", 987654321, 1)
        ), preferences.getBookmarks(bookId))
        assertEquals(before, raw.all)
    }

    @Test fun existingSavedTrackEndAdvancesWithoutRestartingBook() {
        val raw = context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE)
        raw.edit().putInt("track_$bookId", 1).putLong("pos_$bookId", 99000L).commit()
        val progress = PreferencesManager(context).getBookProgress(bookId)
        val finished = LibraryStateStore(context).isFinished(bookId)
        assertFalse(CompletionRules.restartOnPlay(false, finished,
            CompletionRules.isAtEnd(progress.trackIndex, 3, progress.positionMs, 100000L, finished)))
        assertEquals(CompletionRules.ColdStart(2, 0L, false),
            CompletionRules.coldStart(3, { 100000L }, progress, finished))
    }

    @Test fun groupingKeepsProgressIdsForTheSameFilesWithOrWithoutCovers() {
        val files = listOf(
            ScannedAudioFile(Uri.parse("content://books/root"), "primary:Books/Loose.m4b", "Loose.m4b",
                50, 1, "primary:Books", "Books", true, 1000),
            ScannedAudioFile(Uri.parse("content://books/10"), "primary:Books/Dune/10.mp3", "Chapter 10.mp3",
                50, 2, "primary:Books/Dune", "Dune", false, 1000),
            ScannedAudioFile(Uri.parse("content://books/2"), "primary:Books/Dune/2.mp3", "Chapter 2.mp3",
                50, 3, "primary:Books/Dune", "Dune", false, 1000)
        )
        val expected = listOf(bookId, "file:primary:Books/Loose.m4b")
        assertEquals(expected, BookGrouping.group(files).map { it.id })
        val covered = files.map { it.copy(folderCoverUri = Uri.parse("content://books/cover"), folderCoverKey = "cover|10|4") }
        assertEquals(expected, BookGrouping.group(covered).map { it.id })
        val renamedUri = covered.map { it.copy(uri = Uri.parse("content://new-root/${it.name}")) }
        assertEquals(expected, BookGrouping.group(renamedUri).map { it.id })
    }
}
