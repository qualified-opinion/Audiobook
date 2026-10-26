package com.janreins.audiobook

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.C
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.player.BookChapter
import com.janreins.audiobook.player.BookmarkPlace
import com.janreins.audiobook.player.ChapterIndex
import com.janreins.audiobook.ui.components.BookmarkBottomSheet
import com.janreins.audiobook.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkPlaceTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun track(i: Int, title: String, durationMs: Long) =
        AudioTrack(Uri.parse("content://books/$i"), "t$i", "$title.mp3", title, durationMs, 10)
    private val folderBook = Audiobook("folder:b", Uri.parse("content://books/0"), "Book", "2 files",
        tracks = listOf(track(0, "Part 1", 600_000), track(1, "Part 2", 600_000)))
    private fun mark(id: String, track: Int, positionMs: Long, bookId: String = folderBook.id) =
        Bookmark(id, bookId, positionMs, "Note $id", trackIndex = track)

    @Test fun folderBookWithoutChaptersShowsBookTimeAndFile() {
        // Both at 05:12 inside their file: no longer the same label.
        assertEquals(BookmarkPlace(312_000, "Part 1"), BookmarkPlace.of(mark("a", 0, 312_000), folderBook, null))
        assertEquals(BookmarkPlace(912_000, "Part 2"), BookmarkPlace.of(mark("b", 1, 312_000), folderBook, null))
    }

    @Test fun chapterTitleWinsOverTheFile() {
        val chapters = ChapterIndex(listOf(BookChapter(0, 0, 600_000, "Opening", true),
            BookChapter(1, 0, 300_000, "Storm", true), BookChapter(1, 300_000, C.TIME_UNSET, "Calm", true)), true)
        assertEquals("Calm", BookmarkPlace.of(mark("b", 1, 312_000), folderBook, chapters).chapterOrFile)
        assertEquals("Storm", BookmarkPlace.of(mark("c", 1, 10_000), folderBook, chapters).chapterOrFile)
    }

    @Test fun singleFileOrUnknownBookKeepsTheFileTimeOnly() {
        val single = folderBook.copy(id = "file:s", tracks = folderBook.tracks.take(1))
        assertEquals(BookmarkPlace(312_000, null), BookmarkPlace.of(mark("a", 0, 312_000, single.id), single, null))
        assertEquals(BookmarkPlace(312_000, null), BookmarkPlace.of(mark("b", 1, 312_000), null, null))
        assertEquals(BookmarkPlace(312_000, null), BookmarkPlace.of(mark("b", 1, 312_000), single, null))
    }

    @Test fun sheetShowsWholeBookTimesAndFiles() {
        composeTestRule.setContent {
            AudiobookTheme {
                BookmarkBottomSheet(bookmarks = listOf(mark("a", 0, 312_000), mark("b", 1, 312_000)),
                    onDismiss = {}, onSelectBookmark = {}, onDeleteBookmark = {}, book = folderBook)
            }
        }
        composeTestRule.onNodeWithText("05:12").assertIsDisplayed()
        composeTestRule.onNodeWithText("15:12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Part 2").assertIsDisplayed()
    }
}
