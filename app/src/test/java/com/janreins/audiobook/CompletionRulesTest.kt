package com.janreins.audiobook

import com.janreins.audiobook.player.CompletionRules
import com.janreins.audiobook.player.PlaybackPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionRulesTest {
    private val trackCount = 3
    private val lastTrack = 2
    private val lastTrackDuration = 600_000L

    @Test fun finishSeekBackThenPlayContinuesFromThere() {
        // Finish: the player stores the end position of the last track and marks the book finished.
        val endPosition = PlaybackPositions.positionAfterCompletion(lastTrackDuration)
        var finished = true
        assertTrue(CompletionRules.isAtEnd(lastTrack, trackCount, endPosition, lastTrackDuration, finished))

        // Seek back 2 minutes: no longer at the end, so the Finished flag clears.
        val seekTarget = endPosition - 120_000L
        val atEnd = CompletionRules.isAtEnd(lastTrack, trackCount, seekTarget, lastTrackDuration, finished)
        assertFalse(atEnd)
        finished = CompletionRules.finishedAfterSeek(finished, atEnd)
        assertFalse(finished)

        // Play: not ended and not at the end, so playback continues from the seek target.
        assertFalse(CompletionRules.restartOnPlay(ended = false, finished = finished, atEnd = atEnd))
        assertEquals(seekTarget, PlaybackPositions.resumePoint(lastTrack, trackCount, seekTarget, lastTrackDuration).second)
    }

    @Test fun playAfterTheEndRestartsTheBook() {
        assertTrue(CompletionRules.restartOnPlay(ended = true, finished = true, atEnd = false))
        val atEnd = CompletionRules.isAtEnd(lastTrack, trackCount, lastTrackDuration - 500L, lastTrackDuration, true)
        assertTrue(CompletionRules.restartOnPlay(ended = false, finished = true, atEnd = atEnd))
    }

    @Test fun restartOnPlayTruthTable() {
        for (ended in listOf(false, true)) for (finished in listOf(false, true)) for (atEnd in listOf(false, true)) {
            assertEquals(ended || (finished && atEnd), CompletionRules.restartOnPlay(ended, finished, atEnd))
        }
    }

    @Test fun unfinishedBookNearEndContinues() {
        val atEnd = CompletionRules.isAtEnd(lastTrack, trackCount, lastTrackDuration - 1000, lastTrackDuration, false)
        assertTrue(atEnd)
        assertFalse(CompletionRules.restartOnPlay(false, false, atEnd))
    }

    @Test fun seekWithinTheEndKeepsFinished() {
        val atEnd = CompletionRules.isAtEnd(lastTrack, trackCount, lastTrackDuration - 1_000L, lastTrackDuration, true)
        assertTrue(CompletionRules.finishedAfterSeek(true, atEnd))
    }

    @Test fun earlierTracksAreNeverAtTheEnd() {
        assertFalse(CompletionRules.isAtEnd(0, trackCount, lastTrackDuration, lastTrackDuration, true))
        assertFalse(CompletionRules.isAtEnd(0, 0, 0L, 0L, true))
    }

    @Test fun unknownDurationFallsBackToTheFinishedFlag() {
        assertTrue(CompletionRules.isAtEnd(lastTrack, trackCount, 5_000L, 0L, finished = true))
        assertFalse(CompletionRules.isAtEnd(lastTrack, trackCount, 5_000L, 0L, finished = false))
    }
}
