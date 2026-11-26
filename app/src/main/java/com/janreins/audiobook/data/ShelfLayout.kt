package com.janreins.audiobook.data

import com.janreins.audiobook.data.model.Audiobook
import kotlin.math.roundToInt

/** How the library is shown: books standing on shelves, or the older list of cards. */
enum class LibraryView { SHELVES, LIST }

/** The shelves of the bookcase, top to bottom. */
enum class Shelf { CONTINUE_LISTENING, UP_NEXT, FINISHED }

data class ShelfSection(val shelf: Shelf, val books: List<Audiobook>)

/** Which book stands on which shelf, and how big each spine is. No drawing here, so it can be unit tested. */
object ShelfLayout {
    const val MIN_SPINE_WIDTH_DP = 26
    const val MAX_SPINE_WIDTH_DP = 42
    private const val UNKNOWN_LENGTH_WIDTH_DP = 32
    private const val HOURS_FOR_THICKEST_SPINE = 20.0

    /**
     * Puts each book on its shelf, keeping the order of [books] (already sorted and searched) within
     * each shelf. Empty shelves are left out. Same rules as the In progress / Not started / Finished filters.
     */
    fun group(books: List<Audiobook>, statusOf: (Audiobook) -> BookStatus): List<ShelfSection> {
        val byShelf = books.groupBy { shelfOf(statusOf(it)) }
        return Shelf.entries.mapNotNull { shelf -> byShelf[shelf]?.let { ShelfSection(shelf, it) } }
    }

    fun shelfOf(status: BookStatus): Shelf = when {
        status.finished -> Shelf.FINISHED
        status.started || status.progressFraction > 0f -> Shelf.CONTINUE_LISTENING
        else -> Shelf.UP_NEXT
    }

    /** Longer books get thicker spines, up to the thickest at 20 hours. Unknown length: a middle width. */
    fun spineWidthDp(durationMs: Long): Int {
        if (durationMs <= 0L) return UNKNOWN_LENGTH_WIDTH_DP
        val hours = (durationMs / 3_600_000.0).coerceAtMost(HOURS_FOR_THICKEST_SPINE)
        return (MIN_SPINE_WIDTH_DP + (MAX_SPINE_WIDTH_DP - MIN_SPINE_WIDTH_DP) * hours / HOURS_FOR_THICKEST_SPINE).roundToInt()
    }

    /** Heights vary a little from book to book, like a real shelf, and stay the same for the same book. */
    fun spineHeightDp(bookId: String): Int = 128 + Math.floorMod(bookId.hashCode(), 5) * 6

    /**
     * Splits a shelf's spines into rows that each fit [maxWidth] (spines [gap] apart), in order.
     * A spine wider than the whole row still gets a row of its own.
     */
    fun packRows(widths: List<Int>, maxWidth: Int, gap: Int): List<IntRange> {
        val rows = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        widths.forEachIndexed { i, width ->
            if (i > start && used + gap + width > maxWidth) {
                rows += start until i
                start = i
                used = width
            } else {
                used = if (i == start) width else used + gap + width
            }
        }
        if (start < widths.size) rows += start until widths.size
        return rows
    }
}
