package com.janreins.audiobook.player

import androidx.media3.common.Player

/** Both app play buttons use this decision through togglePlayPause -> resume. */
object PlayDecision {
    fun needsPlayBook(hasCurrentMediaItem: Boolean, sameBook: Boolean, playbackState: Int,
                      finished: Boolean, index: Int, count: Int, positionMs: Long, durationMs: Long): Boolean =
        !hasCurrentMediaItem || (sameBook && CompletionRules.restartOnPlay(
            playbackState == Player.STATE_ENDED, finished,
            CompletionRules.isAtEnd(index, count, positionMs, durationMs, finished)
        ))
}
