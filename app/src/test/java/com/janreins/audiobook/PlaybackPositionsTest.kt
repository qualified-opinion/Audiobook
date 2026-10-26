package com.janreins.audiobook

import com.janreins.audiobook.player.PlaybackPositions
import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionsTest {
    @Test fun tracksNoLongerThanTheToleranceAreNotNearTheirEndAtTheStart() {
        // Regression (#11 review): a 1.5 s first track at position 0 must not skip to track 2.
        assertEquals(0 to 0L, PlaybackPositions.resumePoint(0, 3, 0, 1500))
        assertEquals(1 to 500L, PlaybackPositions.resumePoint(1, 3, 500, 1500))
        assertEquals(2 to 0L, PlaybackPositions.resumePoint(2, 3, 0, 1500))
        assertEquals(0 to 0L, PlaybackPositions.resumePoint(0, 3, 0, 2000))
        assertEquals(1 to 0L, PlaybackPositions.resumePoint(0, 3, 1, 2001))
    }

    @Test fun savedProgressAdvancesFromNonLastTrackEnd() {
        for (position in listOf(8000L, 9000L, 10000L, 12000L)) {
            assertEquals(1 to 0L, PlaybackPositions.resumePoint(0, 3, position, 10000))
        }
    }

    @Test fun unfinishedLastTrackPlaysTheTailIncludingSingleTrackBooks() {
        assertEquals(2 to 8000L, PlaybackPositions.resumePoint(2, 3, 9000, 10000))
        assertEquals(0 to 8000L, PlaybackPositions.resumePoint(0, 1, 10000, 10000))
        // A track no longer than the 2 s tolerance has no "tail": it resumes where it was.
        assertEquals(0 to 500L, PlaybackPositions.resumePoint(0, 1, 500, 1000))
    }

    @Test fun ordinaryAndUnknownDurationProgressIsClamped() {
        assertEquals(1 to 3000L, PlaybackPositions.resumePoint(1, 3, 3000, 10000))
        assertEquals(0 to 0L, PlaybackPositions.resumePoint(0, 3, -1, 10000))
        assertEquals(0 to 0L, PlaybackPositions.resumePoint(0, 3, -1, 1000))
        assertEquals(1 to 3000L, PlaybackPositions.resumePoint(1, 3, 3000, 0))
        assertEquals(1 to 3000L, PlaybackPositions.resumePoint(1, 3, 3000, -1))
        assertEquals(1 to 0L, PlaybackPositions.resumePoint(1, 3, -1, 0))
    }

    @Test fun bookmarkOneSecondBeforeTrackEndKeepsExactPosition() {
        // Explicit bookmark/chapter positions are only clamped, never moved by the end tolerance.
        assertEquals(9000L, PlaybackPositions.clamp(9000, 10000))
        assertEquals(10000L, PlaybackPositions.clamp(12000, 10000))
        assertEquals(12000L, PlaybackPositions.clamp(12000, 0))
        assertEquals(0L, PlaybackPositions.clamp(-1, 10000))
    }

    @Test fun completionKeepsEndPosition() {
        assertEquals(10000L, PlaybackPositions.positionAfterCompletion(10000L))
        assertEquals(0L, PlaybackPositions.positionAfterCompletion(-1L))
    }

    @Test fun savesAreThrottled() {
        val throttle = PlaybackPositions.SaveThrottle()
        assertTrue(throttle.shouldSave(1000))
        throttle.markSaved(1000)
        assertFalse(throttle.shouldSave(5999))
        assertTrue(throttle.shouldSave(6000))
        throttle.markSaved(6000)
        assertFalse(throttle.shouldSave(6001))
        assertTrue(throttle.shouldSave(11001))
    }

    @Test fun bookPositionAddsTheLengthOfEarlierFiles() {
        val tracks = listOf(60_000L, 120_000L, 30_000L)
        assertEquals(5_000L, PlaybackPositions.bookPosition(tracks, 0, 5_000))
        assertEquals(185_000L, PlaybackPositions.bookPosition(tracks, 2, 5_000))
        // Unknown (0) lengths and bad input never make the time go negative.
        assertEquals(60_000L, PlaybackPositions.bookPosition(listOf(0L, 60_000L), 2, -1))
    }
}
