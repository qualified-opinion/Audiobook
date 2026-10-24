package com.janreins.audiobook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.janreins.audiobook.ui.components.AddBookmarkDialog
import com.janreins.audiobook.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddBookmarkDialogTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test fun savesThePositionFromWhenTheDialogOpened() {
        var position by mutableLongStateOf(65_000L)
        var track by mutableIntStateOf(2)
        var saved: Triple<String, Int, Long>? = null
        composeTestRule.setContent {
            AudiobookTheme {
                AddBookmarkDialog(
                    currentPositionMs = position,
                    currentTrackIndex = track,
                    onDismiss = {},
                    onSaveBookmark = { title, trackIndex, positionMs -> saved = Triple(title, trackIndex, positionMs) }
                )
            }
        }
        // Playback carries on (and even moves to the next file) while the note is typed.
        position = 3_000L
        track = 3
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Timestamp: 01:05").assertIsDisplayed()

        composeTestRule.onNodeWithTag("save_bookmark_button").performClick()

        assertEquals(Triple("Bookmark at 01:05", 2, 65_000L), saved)
    }
}
