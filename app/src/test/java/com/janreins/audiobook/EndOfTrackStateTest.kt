package com.janreins.audiobook

import com.janreins.audiobook.player.EndOfTrackState
import com.janreins.audiobook.player.EndOfTrackState.Decision.*
import com.janreins.audiobook.player.EotTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class EndOfTrackStateTest {
    private val target = EotTarget("book", 2, 100_000, 1_100_000)
    private fun decide(book: String? = "book", index: Int = 2, age: Long = 1000,
                       bootShift: Long = 0) = EndOfTrackState.decide(target, book, index,
        target.updatedAtElapsedRealtimeMs + age, target.updatedAtWallTimeMs + age + bootShift)

    @Test fun matchingBookAndTrackEnable() = assertEquals(ENABLE, decide())
    @Test fun otherBookClears() = assertEquals(CLEAR, decide(book = "other"))
    @Test fun otherTrackClears() = assertEquals(CLEAR, decide(index = 1))
    @Test fun noItemYetWaits() = assertEquals(WAIT, decide(book = null))
    @Test fun missingTargetClears() = assertEquals(CLEAR, EndOfTrackState.decide(null, "book", 2, 0, 0))
    @Test fun thirtyMinuteBoundary() {
        assertEquals(ENABLE, decide(age = EndOfTrackState.MAX_AGE_MS))
        assertEquals(CLEAR, decide(age = EndOfTrackState.MAX_AGE_MS + 1))
        assertEquals(CLEAR, decide(book = null, age = EndOfTrackState.MAX_AGE_MS + 1))
    }
    @Test fun rebootClearsEvenIfElapsedTimeCaughtUp() {
        assertEquals(CLEAR, decide(bootShift = 5001))
        assertEquals(CLEAR, decide(bootShift = -5001))
        assertEquals(CLEAR, decide(age = -1))
        assertEquals(ENABLE, decide(bootShift = 5000))
        assertEquals(CLEAR, decide(book = null, bootShift = 6000))
    }
}
