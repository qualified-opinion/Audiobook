package com.janreins.audiobook.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook

@OptIn(UnstableApi::class)
object BookMediaItems {
    fun tracks(book: Audiobook) = book.tracks.ifEmpty {
        listOf(AudioTrack(book.uri, book.id, book.fileName, book.title, book.durationMs, book.sizeBytes, book.lastModified))
    }

    fun forBook(book: Audiobook): List<MediaItem> = tracks(book).mapIndexed { index, track ->
        MediaItem.Builder().setUri(track.uri).setMediaId("${book.id}#$index")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
                .setArtist("Audiobook").setAlbumTitle(book.title)
                // CacheBitmapLoader must not match the folder cover against later embedded artwork.
                .setExtras(Bundle().apply {
                    putString(CoverBitmapLoader.EXTRA_BOOK_ID, book.id)
                    book.coverUri?.let { putString(CoverBitmapLoader.EXTRA_FOLDER_COVER_URI, it.toString()) }
                }).build()).build()
    }
}
