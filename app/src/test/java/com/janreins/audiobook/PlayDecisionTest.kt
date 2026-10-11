package com.janreins.audiobook

import androidx.media3.common.Player
import com.janreins.audiobook.player.CompletionRules
import com.janreins.audiobook.player.PlayDecision
import org.junit.Assert.*
import org.junit.Test

class PlayDecisionTest {
    private fun appPlayRestartsFinishedEnd() {
        assertTrue(PlayDecision.needsPlayBook(true, true, Player.STATE_READY, true, 2, 3, 99000, 100000))
        assertTrue(CompletionRules.restartOnPlay(false, true,
            CompletionRules.isAtEnd(2, 3, 99000, 100000, true)))
        assertEquals(CompletionRules.ColdStart(0, 0L, true), CompletionRules.coldStart(3, { 100000L },
            com.janreins.audiobook.data.model.BookProgress(2, 99000), finished = true))
    }

    @Test fun inAppPlayerButtonRestartsInOnePress() = appPlayRestartsFinishedEnd()
    @Test fun miniPlayerButtonRestartsInOnePress() = appPlayRestartsFinishedEnd()

    @Test fun clearedQueueLoadsAndRestartsFinishedUnknownDurationBook() {
        assertTrue(PlayDecision.needsPlayBook(false, false, Player.STATE_IDLE, true, 0, 0, 0, 0))
        // playBook's not-loaded path reads the saved last track and Finished flag.
        assertTrue(CompletionRules.restartOnPlay(false, true, CompletionRules.isAtEnd(2, 3, 0, 0, true)))
        assertFalse(CompletionRules.restartOnPlay(false, false, CompletionRules.isAtEnd(2, 3, 0, 0, false)))
    }

    @Test fun continueUnfinishedTailOrFinishedBookSoughtBack() {
        assertFalse(PlayDecision.needsPlayBook(true, true, Player.STATE_READY, false, 2, 3, 99000, 100000))
        assertFalse(PlayDecision.needsPlayBook(true, true, Player.STATE_READY, true, 2, 3, 70000, 100000))
        assertFalse(PlayDecision.needsPlayBook(true, true, Player.STATE_READY, true, 1, 3, 99000, 100000))
    }

    @Test fun endedLoadedBookRestartsButOtherBooksAreGuarded() {
        assertTrue(PlayDecision.needsPlayBook(true, true, Player.STATE_ENDED, false, 2, 3, 100000, 100000))
        assertFalse(PlayDecision.needsPlayBook(true, false, Player.STATE_ENDED, true, 2, 3, 100000, 100000))
    }
}
