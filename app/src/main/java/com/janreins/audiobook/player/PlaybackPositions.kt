package com.janreins.audiobook.player

object PlaybackPositions {
    fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    /** Saved progress near a track end advances, or plays the final two seconds of the book. */
    fun resumePoint(trackIndex: Int, trackCount: Int, savedMs: Long, trackDurationMs: Long): Pair<Int, Long> {
        val index = trackIndex.coerceIn(0, (trackCount - 1).coerceAtLeast(0))
        // A track no longer than the tolerance is never "near its end" at its start.
        if (savedMs >= 0 && trackDurationMs > CompletionRules.END_TOLERANCE_MS &&
            savedMs >= trackDurationMs - CompletionRules.END_TOLERANCE_MS) {
            return if (index < trackCount - 1) index + 1 to 0L
            else index to (trackDurationMs - CompletionRules.END_TOLERANCE_MS).coerceAtLeast(0L)
        }
        return index to clamp(savedMs, trackDurationMs)
    }

    /** Time from the start of the whole book: the full length of earlier files plus the spot in this one. */
    fun bookPosition(trackDurationsMs: List<Long>, trackIndex: Int, positionMs: Long): Long =
        trackDurationsMs.take(trackIndex.coerceAtLeast(0)).sumOf { it.coerceAtLeast(0L) } + positionMs.coerceAtLeast(0L)

    fun positionAfterCompletion(durationMs: Long): Long = durationMs.coerceAtLeast(0L)

    class SaveThrottle(private val intervalMs: Long = 5000) {
        private var lastSavedMs: Long? = null
        fun shouldSave(nowMs: Long): Boolean = lastSavedMs?.let { nowMs - it >= intervalMs } ?: true
        fun markSaved(nowMs: Long) { lastSavedMs = nowMs }
    }
}
