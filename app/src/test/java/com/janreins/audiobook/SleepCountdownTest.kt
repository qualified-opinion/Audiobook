package com.janreins.audiobook

import com.janreins.audiobook.player.SleepCountdown
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepCountdownTest {
    @Test fun countsDownWhilePlaying() {
        val timer = SleepCountdown(60_000L, nowMs = 1_000L, running = true)
        assertEquals(60_000L, timer.remainingMs(1_000L))
        assertEquals(45_000L, timer.remainingMs(16_000L))
        assertEquals(0L, timer.remainingMs(120_000L))
    }

    @Test fun pauseFreezesRemainingTimeAndPlayContinues() {
        val timer = SleepCountdown(60_000L, nowMs = 0L, running = true)
        timer.pause(nowMs = 20_000L)
        assertFalse(timer.isRunning)
        // A ten-minute phone call doesn't use up the timer.
        assertEquals(40_000L, timer.remainingMs(620_000L))
        timer.resume(nowMs = 620_000L)
        assertTrue(timer.isRunning)
        assertEquals(30_000L, timer.remainingMs(630_000L))
    }

    @Test fun repeatedPauseOrPlayIsHarmless() {
        val timer = SleepCountdown(60_000L, nowMs = 0L, running = true)
        timer.pause(10_000L)
        timer.pause(30_000L)
        timer.resume(40_000L)
        timer.resume(50_000L)
        assertEquals(40_000L, timer.remainingMs(50_000L))
    }

    @Test fun timerSetWhilePausedStartsCountingOnPlay() {
        val timer = SleepCountdown(15 * 60_000L, nowMs = 0L, running = false)
        assertEquals(15 * 60_000L, timer.remainingMs(300_000L))
        timer.resume(300_000L)
        assertEquals(15 * 60_000L - 1_000L, timer.remainingMs(301_000L))
    }

    @Test fun extendAddsFiveMinutesWithoutLosingASecond() {
        val timer = SleepCountdown(60_000L, nowMs = 0L, running = true)
        // 10.5 s in: 49.5 s left, shown as 50.
        assertEquals(50, SleepCountdown.displaySeconds(timer.remainingMs(10_500L)))
        timer.extend(300_000L)
        // Same rounding as the regular tick: 349.5 s shows as 350 (5:50), not 349.
        assertEquals(350, SleepCountdown.displaySeconds(timer.remainingMs(10_500L)))
        timer.pause(10_500L)
        timer.extend(300_000L)
        assertEquals(649_500L, timer.remainingMs(99_999L))
    }

    @Test fun displayRoundsUpSoZeroMeansDone() {
        assertEquals(1, SleepCountdown.displaySeconds(1L))
        assertEquals(1, SleepCountdown.displaySeconds(1_000L))
        assertEquals(2, SleepCountdown.displaySeconds(1_001L))
        assertEquals(0, SleepCountdown.displaySeconds(0L))
        assertEquals(0, SleepCountdown.displaySeconds(-5L))
    }

    @Test fun formatsMinutesAndSeconds() {
        assertEquals("05:07", SleepCountdown.format(307, Locale.US))
        assertEquals("90:00", SleepCountdown.format(5_400, Locale.US))
        assertEquals("00:00", SleepCountdown.format(-3, Locale.US))
    }

    @Test fun negativeDurationIsTreatedAsFinished() {
        assertEquals(0L, SleepCountdown(-1L, 0L, running = true).remainingMs(0L))
    }
}
