package com.janreins.audiobook.player

import java.util.Locale

/**
 * Time left on a minutes-based sleep timer. The countdown only runs while audio is playing, so
 * pausing (for a phone call, say) keeps the remaining time until playback starts again.
 * Times come from a monotonic clock (SystemClock.elapsedRealtime) passed in, which keeps this testable.
 */
class SleepCountdown(durationMs: Long, nowMs: Long, running: Boolean) {
    private var remainingAtStartMs = durationMs.coerceAtLeast(0L)
    private var runningSinceMs: Long? = if (running) nowMs else null

    val isRunning get() = runningSinceMs != null

    fun remainingMs(nowMs: Long): Long {
        val elapsed = runningSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return (remainingAtStartMs - elapsed).coerceAtLeast(0L)
    }

    fun pause(nowMs: Long) {
        if (!isRunning) return
        remainingAtStartMs = remainingMs(nowMs)
        runningSinceMs = null
    }

    fun resume(nowMs: Long) {
        if (!isRunning) runningSinceMs = nowMs
    }

    fun extend(extraMs: Long) {
        remainingAtStartMs += extraMs
    }

    companion object {
        /** Whole seconds shown to the listener, rounded up so the display reaches 00:00 only at the end. */
        fun displaySeconds(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L).toInt()

        /** "mm:ss" in the phone's digits, on purpose, so it matches the book times shown on the same screen. */
        fun format(remainingSeconds: Int, locale: Locale = Locale.getDefault()): String {
            val s = remainingSeconds.coerceAtLeast(0)
            return String.format(locale, "%02d:%02d", s / 60, s % 60)
        }
    }
}
