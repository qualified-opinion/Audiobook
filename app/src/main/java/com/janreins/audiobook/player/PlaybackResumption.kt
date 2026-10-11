package com.janreins.audiobook.player

import androidx.core.net.toUri
import android.content.Context
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.janreins.audiobook.data.FolderAccess
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.ResumeSnapshot
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.data.toBook
import com.janreins.audiobook.data.model.BookProgress
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class PlaybackResumption(
    private val snapshot: (Boolean) -> ResumeSnapshot?,
    private val progress: (String) -> BookProgress,
    private val finished: (String) -> Boolean,
    private val access: (String) -> Boolean,
    private val trackExists: (String) -> Boolean,
    private val unavailable: () -> Unit,
    private val executor: Executor
) {
    fun resume(isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.submitAsync({
            fun fail(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                if (isForPlayback) unavailable()
                return Futures.immediateFailedFuture(UnsupportedOperationException("Last book unavailable"))
            }
            try {
                val saved = snapshot(isForPlayback)
                if (saved == null || !access(saved.folderUri)) fail() else {
                    val start = CompletionRules.coldStart(saved.tracks.size, { saved.tracks[it].durationMs },
                        progress(saved.bookId), finished(saved.bookId))
                    if (!trackExists(saved.tracks[start.index].uri)) fail() else {
                        Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                            BookMediaItems.forBook(saved.toBook()), start.index, start.positionMs))
                    }
                }
            } catch (_: Exception) { fail() }
        }, executor)

    companion object {
        fun forContext(context: Context, executor: Executor): PlaybackResumption {
            val prefs = PreferencesManager(context)
            val library = LibraryStateStore(context)
            val store = ResumeSnapshotStore(context)
            return PlaybackResumption({ store.read(deleteInvalid = it) }, prefs::getBookProgress,
                library::isFinished,
                { FolderAccess.hasPersistedReadAccess(context.contentResolver, it.toUri()) },
                { documentExists(context, it) }, prefs::setResumeUnavailable, executor)
        }

        /** One cheap provider query: false when the document is gone, unreadable or the provider is absent. */
        fun documentExists(context: Context, uri: String): Boolean = try {
            context.contentResolver.query(uri.toUri(),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        } catch (_: Exception) { false }
    }
}
