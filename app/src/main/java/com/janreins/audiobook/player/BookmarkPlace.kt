package com.janreins.audiobook.player

import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.Bookmark

/** Where a bookmark is, as shown in the bookmark list: time in the whole book and the chapter or file. */
data class BookmarkPlace(val bookPositionMs: Long, val chapterOrFile: String?) {
    companion object {
        /**
         * A bookmark stores the time inside its file, so two bookmarks in a folder book could both say
         * 05:12. This gives the time from the start of the book (as the chapter list shows it) and the
         * chapter title, or the file title when the book has no chapters. Single-file books without
         * chapters get no second line: the file is the book.
         */
        fun of(bookmark: Bookmark, book: Audiobook?, chapters: ChapterIndex?): BookmarkPlace {
            if (book == null || book.id != bookmark.audiobookId) return BookmarkPlace(bookmark.positionMs, null)
            val position = PlaybackPositions.bookPosition(book.tracks.map { it.durationMs },
                bookmark.trackIndex, bookmark.positionMs)
            val chapter = chapters?.takeIf { it.navigable }
                ?.let { it.chapters.getOrNull(it.indexAt(bookmark.trackIndex, bookmark.positionMs)) }
                ?.title?.takeIf { it.isNotBlank() }
            val file = if (book.tracks.size > 1) book.tracks.getOrNull(bookmark.trackIndex)?.title else null
            return BookmarkPlace(position, chapter ?: file)
        }
    }
}
