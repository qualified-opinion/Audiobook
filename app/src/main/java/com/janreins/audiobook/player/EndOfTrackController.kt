package com.janreins.audiobook.player

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** Service-owned end-of-track timer. Pending restore waits for the first loaded book/track. */
class EndOfTrackController(
    private val player: Player,
    private val setPauseAtEnd: (Boolean) -> Unit,
    private val store: EndOfTrackStore,
    private val publish: (Boolean) -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallTime: () -> Long = System::currentTimeMillis
) {
    companion object { const val EXTRA_END_OF_TRACK = "end_of_track" }

    private var target = store.endOfTrackTarget()
    private var enabled = false
    private fun bookId() = player.currentMediaItem?.mediaId?.substringBeforeLast("#")
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = reconcile()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reconcile()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                setEnabled(false)
            } else if (enabled) {
                // Player callbacks can arrive together when replacing a playlist.
                if (bookId() != target?.bookId) setEnabled(false) else saveTarget()
            }
        }
    }

    init {
        player.addListener(listener)
        setPauseAtEnd(false)
        publish(false)
        reconcile()
    }

    fun setEnabled(value: Boolean) {
        if (!value || bookId() == null) {
            target = null
            store.clearEndOfTrackTarget()
            apply(false)
        } else {
            saveTarget()
            apply(true)
        }
    }

    private fun saveTarget() {
        val book = bookId() ?: return
        target = EotTarget(book, player.currentMediaItemIndex, elapsedRealtime(), wallTime())
        store.saveEndOfTrackTarget(requireNotNull(target))
    }

    private fun apply(value: Boolean) {
        enabled = value
        setPauseAtEnd(value)
        publish(value)
    }

    private fun reconcile() {
        if (enabled) {
            if (bookId() != target?.bookId) setEnabled(false)
            else if (player.currentMediaItemIndex != target?.trackIndex) saveTarget()
            return
        }
        when (EndOfTrackState.decide(target, bookId(), player.currentMediaItemIndex, elapsedRealtime(), wallTime())) {
            EndOfTrackState.Decision.ENABLE -> apply(true)
            EndOfTrackState.Decision.CLEAR -> setEnabled(false)
            EndOfTrackState.Decision.WAIT -> Unit
        }
    }

    /** Service destruction preserves the target for a later restore. */
    fun release() = player.removeListener(listener)
}
