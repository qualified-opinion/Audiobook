package com.janreins.audiobook.player

import com.janreins.audiobook.data.model.BookProgress

/** When a book counts as "at the end", and what that means for resume and the Finished flag. */
object CompletionRules {
    /** The last 2 s count as the end. */
    const val END_TOLERANCE_MS = 2_000L

    /**
     * True when [positionMs] in [trackIndex] is at the end of the whole book. With an unknown track
     * duration the position can't tell, so the stored Finished flag decides (last track only).
     */
    fun isAtEnd(
        trackIndex: Int, trackCount: Int, positionMs: Long, trackDurationMs: Long, finished: Boolean
    ): Boolean {
        if (trackCount <= 0 || trackIndex < trackCount - 1) return false
        if (trackDurationMs <= 0L) return finished
        return positionMs >= trackDurationMs - END_TOLERANCE_MS
    }

    /**
     * Pressing play restarts the book from the first track only if playback actually ended or the
     * book is Finished at the end. An unfinished book near the end still plays its tail.
     */
    fun restartOnPlay(ended: Boolean, finished: Boolean, atEnd: Boolean): Boolean = ended || (finished && atEnd)

    data class ColdStart(val index: Int, val positionMs: Long, val restart: Boolean)

    /** One decision for an unloaded app queue, a cold headset press and the receiver's target. */
    fun coldStart(trackCount: Int, duration: (Int) -> Long, saved: BookProgress, finished: Boolean): ColdStart {
        require(trackCount > 0)
        val index = saved.trackIndex.coerceIn(0, trackCount - 1)
        val restart = restartOnPlay(false, finished,
            isAtEnd(index, trackCount, saved.positionMs, duration(index), finished))
        if (restart) return ColdStart(0, 0L, true)
        val (startIndex, position) = PlaybackPositions.resumePoint(index, trackCount, saved.positionMs, duration(index))
        return ColdStart(startIndex, position, false)
    }

    /** An explicit seek, skip or scrub away from the end means the book is being listened to again. */
    fun finishedAfterSeek(wasFinished: Boolean, atEnd: Boolean): Boolean = wasFinished && atEnd
}
