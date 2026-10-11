package com.janreins.audiobook.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

interface SessionSettings {
    fun skipBackMs(): Long
    fun skipForwardMs(): Long
    fun smartRewindEnabled(): Boolean
    fun isFinished(bookId: String): Boolean
}

@OptIn(UnstableApi::class)
class SessionPlayer(
    player: Player,
    private val settings: SessionSettings,
    private val chapters: (bookId: String) -> ChapterIndex?,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : ForwardingSimpleBasePlayer(player) {
    private var pausedAtMs: Long? = null
    private var pausedMediaId: String? = null
    private val pauseListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && player.currentMediaItem != null && pausedAtMs == null) {
                pausedAtMs = clock()
                pausedMediaId = player.currentMediaItem?.mediaId
            } else if (playWhenReady) clearPause()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) clearPause()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) clearPause()
        }
    }
    init { player.addListener(pauseListener) }
    private fun clearPause() { pausedAtMs = null; pausedMediaId = null }
    private fun chapterIndex() = player.currentMediaItem?.mediaId?.substringBeforeLast("#")?.let(chapters)
    fun invalidate() = invalidateState()

    override fun getState(): State {
        val state = super.getState()
        val builder = state.buildUpon().setSeekBackIncrementMs(settings.skipBackMs())
            .setSeekForwardIncrementMs(settings.skipForwardMs())
        val index = chapterIndex()
        if (index?.navigable == true) {
            builder.setAvailableCommands(state.availableCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build())
            if (!state.timeline.isEmpty) {
                val title = index.chapters[index.indexAt(player.currentMediaItemIndex, player.currentPosition)].title
                builder.setPlaylist(state.timeline, state.currentTracks, state.currentMetadata.buildUpon().setSubtitle(title).setArtist(title).build())
            }
        }
        return builder.build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> player.seekTo(SeekMath.clampSeek(player.currentPosition, -settings.skipBackMs(), player.duration))
            Player.COMMAND_SEEK_FORWARD -> player.seekTo(SeekMath.clampSeek(player.currentPosition, settings.skipForwardMs(), player.duration))
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                else return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT -> {
                if (!ChapterNavigation.seek(player, chapterIndex(), seekCommand == Player.COMMAND_SEEK_TO_NEXT))
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            // MediaSession supplies an empty queue through its resumption callback before calling play.
            val item = player.currentMediaItem ?: return Futures.immediateVoidFuture()
            val finished = settings.isFinished(item.mediaId.substringBeforeLast("#"))
            val atEnd = CompletionRules.isAtEnd(player.currentMediaItemIndex, player.mediaItemCount,
                player.currentPosition, player.duration, finished)
            if (CompletionRules.restartOnPlay(player.playbackState == Player.STATE_ENDED, finished, atEnd)) {
                player.seekTo(0, 0L)
                clearPause()
            } else applySmartRewind()
        }
        player.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    private fun applySmartRewind() {
        val pausedAt = pausedAtMs ?: return
        val mediaId = pausedMediaId
        clearPause()
        if (player.playWhenReady || !settings.smartRewindEnabled() || mediaId != player.currentMediaItem?.mediaId) return
        val rewind = SmartRewind.rewindMsFor(clock() - pausedAt)
        if (rewind > 0) player.seekTo(SeekMath.clampSeek(player.currentPosition, -rewind, player.duration))
    }

    override fun handleRelease(): ListenableFuture<*> {
        player.removeListener(pauseListener)
        return super.handleRelease()
    }
}
