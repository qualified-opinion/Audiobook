package com.janreins.audiobook

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.ui.screens.LibraryScreen
import com.janreins.audiobook.ui.screens.scanningLabel
import com.janreins.audiobook.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRefreshTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun show(books: List<Audiobook>, loading: Boolean, found: Int) = composeTestRule.setContent {
        AudiobookTheme {
            LibraryScreen(
                audiobooks = books, isLoading = loading, currentPlayingBook = null, isPlaying = false,
                getSavedPosition = { 0L }, onBookClick = {}, onMiniPlayerClick = {}, onTogglePlayPause = {},
                onChangeFolderClick = {}, onRefresh = {}, scannedFileCount = found
            )
        }
    }

    @Test fun rescanKeepsTheListVisibleWithAProgressLine() {
        show(listOf(Audiobook("folder:a", Uri.parse("content://books/a"), "Dune", "Dune.m4b")), loading = true, found = 12)
        composeTestRule.onNodeWithText("Dune").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_refresh_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText(scanningLabel(12)).assertIsDisplayed()
    }

    @Test fun firstScanShowsTheCountWhileTheListIsEmpty() {
        show(emptyList(), loading = true, found = 1)
        composeTestRule.onNodeWithText("Scanning folder and sub-folders... 1 audio file found").assertIsDisplayed()
    }

    @Test fun labels() {
        assertEquals("Scanning folder and sub-folders...", scanningLabel(0))
        assertEquals("Scanning folder and sub-folders... 3 audio files found", scanningLabel(3))
    }
}
