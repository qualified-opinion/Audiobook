package com.janreins.audiobook

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.janreins.audiobook.data.BookStatus
import com.janreins.audiobook.data.LibraryFilter
import com.janreins.audiobook.data.LibraryView
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.ui.screens.LibraryScreen
import com.janreins.audiobook.ui.theme.AppThemeMode
import com.janreins.audiobook.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class BookshelfScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val hour = 3_600_000L
    private val titles = listOf(
        "The Salt Road", "Quiet Harbour", "The Long Ledger", "Glass Orchard", "Midnight Ferry", "Paper Lanterns",
        "Copper Hills", "Tidewater", "Winter Letters", "Blue Hour", "Lamplight", "Salt and Ash",
        "River of Stone", "A Year of Small Things", "Northern Wind", "Harbour Lights", "The Quiet Year", "Orchard Keeper"
    )
    private val books = titles.mapIndexed { i, title ->
        Audiobook("folder:$i", Uri.parse("content://books/$i"), title, "$title.m4b", durationMs = (3 + (i * 7) % 18) * hour)
    }
    private val statuses = books.associate { book ->
        val i = book.id.substringAfter(':').toInt()
        book.id to when {
            i < 3 -> BookStatus(listOf(0.42f, 0.71f, 0.12f)[i], false, 100L - i)
            i >= 12 -> BookStatus(1f, true, 10L)
            else -> BookStatus(0f, false, null)
        }
    }

    private fun show(
        themeMode: AppThemeMode = AppThemeMode.LIGHT,
        view: LibraryView = LibraryView.SHELVES,
        filter: LibraryFilter = LibraryFilter.ALL,
        onBookClick: (Audiobook) -> Unit = {},
        onViewChange: (LibraryView) -> Unit = {}
    ) = composeTestRule.setContent {
        AudiobookTheme(themeMode = themeMode) {
            LibraryScreen(
                audiobooks = books, isLoading = false, currentPlayingBook = books[0], isPlaying = true,
                getSavedPosition = { 0L }, onBookClick = onBookClick, onMiniPlayerClick = {}, onTogglePlayPause = {},
                onChangeFolderClick = {}, onRefresh = {}, statusOf = { statuses.getValue(it.id) },
                filter = filter, view = view, onViewChange = onViewChange
            )
        }
    }

    @Test fun shelvesShowEachGroupAndTheBooksOnThem() {
        show()
        composeTestRule.onNodeWithText("Continue listening").assertIsDisplayed()
        composeTestRule.onNodeWithText("Up next").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("The Salt Road, 42% listened, playing").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Glass Orchard, not started").assertIsDisplayed()
    }

    @Test fun tappingASpinePlaysThatBook() {
        var played: Audiobook? = null
        show(onBookClick = { played = it })
        composeTestRule.onNodeWithContentDescription("Quiet Harbour, 71% listened").performClick()
        assertEquals("Quiet Harbour", played?.title)
    }

    @Test fun aSavedFilterDoesNotHideShelves() {
        show(filter = LibraryFilter.FINISHED)
        composeTestRule.onNodeWithText("Continue listening").assertIsDisplayed()
    }

    @Test fun menuSwitchesToTheList() {
        var chosen: LibraryView? = null
        show(onViewChange = { chosen = it })
        composeTestRule.onNodeWithTag("library_more_button").performClick()
        composeTestRule.onNodeWithText("Show as list").performClick()
        assertEquals(LibraryView.LIST, chosen)
    }

    @Test fun listViewStillWorks() {
        show(view = LibraryView.LIST)
        composeTestRule.onNodeWithText("A Year of Small Things").assertIsDisplayed()
        composeTestRule.onNodeWithText("In progress").assertIsDisplayed()
    }

    // Pictures of the bookshelf for reviewing the look; CI uploads them as the "screenshots" artifact.
    @Test fun bookshelfDay() {
        show(AppThemeMode.LIGHT)
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/bookshelf_day.png")
    }

    @Test fun bookshelfNight() {
        show(AppThemeMode.DARK)
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/bookshelf_night.png")
    }
}
