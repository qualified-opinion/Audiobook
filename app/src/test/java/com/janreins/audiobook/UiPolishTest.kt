package com.janreins.audiobook

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.ui.components.AudiobookListItem
import com.janreins.audiobook.ui.components.BookmarkBottomSheet
import com.janreins.audiobook.ui.components.PlayerControls
import com.janreins.audiobook.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The November 2026 UI review: spoken labels, delete confirmation and the book menu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPolishTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val book = Audiobook("folder:a", Uri.parse("content://books/a"), "Dune", "Dune.m4b", durationMs = 100_000)

    @Test fun skipButtonsHaveSpokenLabels() {
        composeTestRule.setContent {
            AudiobookTheme {
                PlayerControls(isPlaying = false, onTogglePlayPause = {}, onSkipBackward15s = {},
                    onSkipBackward1m = {}, onSkipForward15s = {}, onSkipForward1m = {},
                    skipBackSeconds = 10, skipForwardSeconds = 30)
            }
        }
        composeTestRule.onNodeWithContentDescription("Back 10 seconds").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back 1 minute").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Forward 1 minute").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test fun deletingABookmarkAsksFirst() {
        val deleted = mutableListOf<Bookmark>()
        val mark = Bookmark("m1", book.id, 30_000, "Big reveal")
        composeTestRule.setContent {
            AudiobookTheme {
                BookmarkBottomSheet(bookmarks = listOf(mark), onDismiss = {}, onSelectBookmark = {},
                    onDeleteBookmark = { deleted += it }, book = book)
            }
        }
        composeTestRule.onNodeWithTag("delete_bookmark_m1").performClick()
        assertTrue(deleted.isEmpty())
        composeTestRule.onNodeWithText("Delete bookmark?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_delete_bookmark_button").performClick()
        assertEquals(listOf(mark), deleted)
    }

    @Test fun finishedBookOnlyOffersMarkAsUnplayed() {
        composeTestRule.setContent {
            AudiobookTheme {
                AudiobookListItem(audiobook = book, savedPositionMs = 0, isCurrentlyPlaying = false,
                    onClick = {}, finished = true)
            }
        }
        composeTestRule.onNodeWithContentDescription("Options for Dune").performClick()
        composeTestRule.onNodeWithText("Mark as unplayed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as finished").assertDoesNotExist()
    }
}
