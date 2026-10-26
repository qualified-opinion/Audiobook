package com.janreins.audiobook

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.BackupBook
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.ListeningBackup
import com.janreins.audiobook.data.ListeningBackupStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.RestoreSummary
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.data.model.Bookmark
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListeningBackupTest {
    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var library: LibraryStateStore

    private fun book(id: String, title: String, vararg files: String) = Audiobook(id, Uri.parse("content://x/$id"), title,
        files.first(), tracks = files.map { AudioTrack(Uri.parse("content://x/$it"), it, it, it, 1000L, 1L) })

    private val dune = book("folder:primary:Books/Dune", "Dune", "01.mp3", "02.mp3", "03.mp3")
    private val emma = book("file:primary:Books/Emma.m4b", "Emma", "Emma.m4b")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PreferencesManager(context)
        library = LibraryStateStore(context)
    }

    @Test fun jsonRoundTripKeepsEveryField() {
        val backup = ListeningBackup(123L, listOf(
            BackupBook("folder:a", "Dune", listOf("01.mp3", "02.mp3"), BookProgress(1, 4500L), true, 99L,
                listOf(Bookmark("m1", "folder:a", 700L, "Clue", 50L, 1))),
            BackupBook("file:b", null, emptyList(), null, false, null, emptyList())))
        assertEquals(backup, ListeningBackup.fromJson(backup.toJson()))
    }

    @Test fun otherFilesAreRejected() {
        assertNull(ListeningBackup.fromJson("not json"))
        assertNull(ListeningBackup.fromJson("""{"books":[]}"""))
        assertNull(ListeningBackup.fromJson("""{"format":"audiobook-listening-backup","version":2,"books":[]}"""))
        assertNull(ListeningBackup.fromJson("""{"format":"audiobook-listening-backup","version":1,"books":[{"id":""}]}"""))
    }

    @Test fun matchesByIdThenByTitleAndFileNames() {
        val entry = BackupBook("folder:1234-ABCD:Books/Dune", "Dune", listOf("01.mp3", "02.mp3", "03.mp3"),
            null, false, null, emptyList())
        assertSame(dune, ListeningBackup.match(entry, listOf(emma, dune)))
        assertSame(emma, ListeningBackup.match(entry.copy(id = emma.id), listOf(emma, dune)))
        assertNull(ListeningBackup.match(entry.copy(fileNames = listOf("01.mp3")), listOf(dune)))
        // Two copies of the same book: don't guess.
        assertNull(ListeningBackup.match(entry, listOf(dune, dune.copy(id = "folder:other"))))
    }

    @Test fun olderBackupNeverMovesARecentlyPlayedBookBack() {
        val entry = BackupBook("x", null, emptyList(), BookProgress(0, 10L), false, 100L, emptyList())
        assertTrue(ListeningBackup.shouldReplaceProgress(entry, hasLocalProgress = false, localLastPlayedAt = 500L))
        assertTrue(ListeningBackup.shouldReplaceProgress(entry, hasLocalProgress = true, localLastPlayedAt = 50L))
        assertFalse(ListeningBackup.shouldReplaceProgress(entry, hasLocalProgress = true, localLastPlayedAt = 100L))
        assertFalse(ListeningBackup.shouldReplaceProgress(entry.copy(lastPlayedAt = null), true, null))
        assertFalse(ListeningBackup.shouldReplaceProgress(entry.copy(progress = null), false, null))
    }

    @Test fun exportCollectsBookDataAndSkipsLegacyPerFileKeys() {
        prefs.saveBookProgress(dune.id, BookProgress(2, 300L))
        library.setLastPlayedAt(dune.id, 77L)
        prefs.addBookmark(Bookmark("m1", emma.id, 900L, "Ball", 5L, 0))
        library.setFinished(emma.id, true)
        prefs.savePlaybackPosition("content://old/track.mp3", 42L)

        val backup = ListeningBackupStore(context).export(listOf(dune), now = 1L)

        assertEquals(listOf(emma.id, dune.id).sorted(), backup.books.map { it.id })
        val d = backup.books.first { it.id == dune.id }
        assertEquals(BackupBook(dune.id, "Dune", listOf("01.mp3", "02.mp3", "03.mp3"), BookProgress(2, 300L),
            false, 77L, emptyList()), d)
        val e = backup.books.first { it.id == emma.id }
        assertTrue(e.finished)
        assertNull(e.title) // not in the current library
        assertEquals(listOf("m1"), e.bookmarks.map { it.id })
    }

    @Test fun restoreOnANewPhoneFindsMovedBooksAndKeepsUnknownOnes() {
        val old = ListeningBackup(1L, listOf(
            BackupBook("folder:1234-ABCD:Books/Dune", "Dune", listOf("01.mp3", "02.mp3", "03.mp3"),
                BookProgress(1, 2500L), false, 10L, listOf(Bookmark("m1", "folder:1234-ABCD:Books/Dune", 800L, "Clue", 3L, 2))),
            BackupBook("file:gone", "Gone", listOf("gone.mp3"), BookProgress(0, 5L), true, 11L, emptyList())))
        val changed = mutableListOf<String>()

        val summary = ListeningBackupStore(context).restore(ListeningBackup.fromJson(old.toJson())!!, listOf(dune, emma)) { id, _ ->
            changed += id
        }

        assertEquals(RestoreSummary(restored = 1, notInLibrary = 1), summary)
        assertEquals(BookProgress(1, 2500L), prefs.getBookProgress(dune.id))
        assertEquals(10L, library.getLastPlayedAt(dune.id))
        assertEquals(listOf(Bookmark("m1", dune.id, 800L, "Clue", 3L, 2)), prefs.getBookmarks(dune.id))
        // Saved under its old id so it applies if that folder is chosen later.
        assertEquals(BookProgress(0, 5L), prefs.getBookProgress("file:gone"))
        assertTrue(library.isFinished("file:gone"))
        assertEquals(listOf(dune.id, "file:gone"), changed)
        assertFalse(prefs.hasBookProgress(emma.id))
    }

    @Test fun restoreKeepsNewerLocalProgressAndMergesBookmarks() {
        prefs.saveBookProgress(dune.id, BookProgress(2, 900L))
        library.setLastPlayedAt(dune.id, 500L)
        prefs.addBookmark(Bookmark("local", dune.id, 1L, "Mine", 1L, 0))
        val backup = ListeningBackup(1L, listOf(BackupBook(dune.id, "Dune", emptyList(), BookProgress(0, 1L), true, 100L,
            listOf(Bookmark("local", dune.id, 1L, "Mine", 1L, 0), Bookmark("old", dune.id, 2L, "Old", 2L, 1)))))

        ListeningBackupStore(context).restore(backup, listOf(dune))

        assertEquals(BookProgress(2, 900L), prefs.getBookProgress(dune.id))
        assertFalse(library.isFinished(dune.id))
        assertEquals(listOf("local", "old"), prefs.getBookmarks(dune.id).map { it.id })
    }

    @Test fun restoredTrackIndexStaysInsideTheBook() {
        val backup = ListeningBackup(1L, listOf(BackupBook(emma.id, "Emma", emptyList(), BookProgress(4, 7L), false, 1L, emptyList())))
        ListeningBackupStore(context).restore(backup, listOf(emma))
        assertEquals(BookProgress(0, 7L), prefs.getBookProgress(emma.id))
    }
}
