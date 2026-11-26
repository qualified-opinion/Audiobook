package com.janreins.audiobook

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.BookStatus
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.LibraryView
import com.janreins.audiobook.data.Shelf
import com.janreins.audiobook.data.ShelfLayout
import com.janreins.audiobook.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShelfLayoutTest {
    private fun book(id: String, hours: Double = 0.0) =
        Audiobook(id, Uri.parse("content://books/$id"), "Book $id", "$id.mp3", (hours * 3_600_000).toLong())

    @Test fun booksGoOnTheShelfMatchingTheirStatusAndKeepTheirOrder() {
        val books = listOf(book("c"), book("a"), book("d"), book("b"), book("e"))
        val statuses = mapOf(
            "a" to BookStatus(0.4f, false, 1L),
            "b" to BookStatus(0f, true, 2L),
            "c" to BookStatus(0f, false, null),
            // Played once but the length is unknown: still Continue listening, as with the In progress filter.
            "d" to BookStatus(0f, false, 3L, started = true),
            "e" to BookStatus(0f, false, null)
        )
        val sections = ShelfLayout.group(books) { statuses.getValue(it.id) }
        assertEquals(listOf(Shelf.CONTINUE_LISTENING, Shelf.UP_NEXT, Shelf.FINISHED), sections.map { it.shelf })
        assertEquals(listOf("a", "d"), sections[0].books.map { it.id })
        assertEquals(listOf("c", "e"), sections[1].books.map { it.id })
        assertEquals(listOf("b"), sections[2].books.map { it.id })
    }

    @Test fun emptyShelvesAreLeftOut() {
        val sections = ShelfLayout.group(listOf(book("a"))) { BookStatus(0f, false, null) }
        assertEquals(listOf(Shelf.UP_NEXT), sections.map { it.shelf })
        assertTrue(ShelfLayout.group(emptyList()) { BookStatus(0f, false, null) }.isEmpty())
    }

    @Test fun finishedWinsOverProgress() {
        assertEquals(Shelf.FINISHED, ShelfLayout.shelfOf(BookStatus(0.99f, true, 1L)))
    }

    @Test fun longerBooksHaveThickerSpinesWithinLimits() {
        assertEquals(ShelfLayout.MIN_SPINE_WIDTH_DP, ShelfLayout.spineWidthDp(1L))
        assertEquals(ShelfLayout.MAX_SPINE_WIDTH_DP, ShelfLayout.spineWidthDp(20L * 3_600_000))
        assertEquals(ShelfLayout.MAX_SPINE_WIDTH_DP, ShelfLayout.spineWidthDp(90L * 3_600_000))
        assertTrue(ShelfLayout.spineWidthDp(5L * 3_600_000) < ShelfLayout.spineWidthDp(10L * 3_600_000))
        assertTrue(ShelfLayout.spineWidthDp(0L) in ShelfLayout.MIN_SPINE_WIDTH_DP..ShelfLayout.MAX_SPINE_WIDTH_DP)
    }

    @Test fun spineHeightIsStableForTheSameBook() {
        val heights = (1..200).map { ShelfLayout.spineHeightDp("folder:$it") }
        assertTrue(heights.all { it in 128..152 })
        assertEquals(ShelfLayout.spineHeightDp("folder:7"), ShelfLayout.spineHeightDp("folder:7"))
        assertTrue(heights.toSet().size > 1)
    }

    @Test fun rowsFitTheShelfWidthAndKeepEveryBookInOrder() {
        val widths = listOf(30, 30, 30, 40, 26, 42, 30)
        val rows = ShelfLayout.packRows(widths, maxWidth = 100, gap = 3)
        assertEquals(listOf(0..2, 3..4, 5..6), rows)
        rows.forEach { row -> assertTrue(row.sumOf { widths[it] } + 3 * (row.count() - 1) <= 100) }
        assertEquals(widths.indices.toList(), rows.flatMap { it.toList() })
    }

    @Test fun aSpineWiderThanTheShelfStillGetsARow() {
        assertEquals(listOf(0..0, 1..1), ShelfLayout.packRows(listOf(50, 10), maxWidth = 40, gap = 3))
        assertTrue(ShelfLayout.packRows(emptyList(), maxWidth = 40, gap = 3).isEmpty())
    }

    @Test fun twoThousandBooksPackQuickly() {
        val widths = (1..2000).map { ShelfLayout.spineWidthDp(it * 400_000L) }
        val start = System.nanoTime()
        val rows = ShelfLayout.packRows(widths, maxWidth = 320, gap = 3)
        assertTrue((System.nanoTime() - start) < 200_000_000L)
        assertEquals(2000, rows.sumOf { it.count() })
    }

    @Test fun bookshelfIsTheDefaultViewAndTheChoiceIsRemembered() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(LibraryView.SHELVES, LibraryStateStore(context).getView())
        LibraryStateStore(context).setView(LibraryView.LIST)
        assertEquals(LibraryView.LIST, LibraryStateStore(context).getView())
    }
}
