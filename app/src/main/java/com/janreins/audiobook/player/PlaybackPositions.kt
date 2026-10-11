package com.janreins.audiobook.player

object PlaybackPositions {
    fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    /** Saved progress near a track end advances, or plays the final two seconds of the book. */
    fun resumePoint(trackIndex: Int, trackCount: Int, savedMs: Long, trackDurationMs: Long): Pair<Int, Long> {
        val index = trackIndex.coerceIn(0, (trackCount - 1).coerceAtLeast(0))
        if (savedMs >= 0 && trackDurationMs > 0 && savedMs >= trackDurationMs - CompletionRules.END_TOLERANCE_MS) {
            return if (index < trackCount - 1) index + 1 to 0L
            else index to (trackDurationMs - CompletionRules.END_TOLERANCE_MS).coerceAtLeast(0L)
        }
        return index to clamp(savedMs, trackDurationMs)
    }

    /** Explicit bookmark/chapter positions are exact; only saved progress uses the end tolerance. */
    fun startPoint(trackIndex: Int, trackCount: Int, positionMs: Long, trackDurationMs: Long,
                   savedProgress: Boolean): Pair<Int, Long> =
        if (savedProgress) resumePoint(trackIndex, trackCount, positionMs, trackDurationMs)
        else trackIndex to clamp(positionMs, trackDurationMs)

    fun positionAfterCompletion(durationMs: Long): Long = durationMs.coerceAtLeast(0L)

    class SaveThrottle(private val intervalMs: Long = 5000) {
        private var lastSavedMs: Long? = null
        fun shouldSave(nowMs: Long): Boolean = lastSavedMs?.let { nowMs - it >= intervalMs } ?: true
        fun markSaved(nowMs: Long) { lastSavedMs = nowMs }
    }
}
