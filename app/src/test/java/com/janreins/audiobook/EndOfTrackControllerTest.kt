package com.janreins.audiobook

import androidx.media3.common.Player
import com.janreins.audiobook.player.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndOfTrackControllerTest {
    private class Store(var target: EotTarget? = null) : EndOfTrackStore {
        override fun endOfTrackTarget() = target
        override fun saveEndOfTrackTarget(target: EotTarget) { this.target = target }
        override fun clearEndOfTrackTarget() { target = null }
    }
    private val player = SessionPlayerTest.FakePlayer()
    private val store = Store()
    private var elapsed = 100_000L
    private var wall = 1_100_000L
    private var pauseAtEnd = false
    private val published = mutableListOf<Boolean>()
    private fun controller() = EndOfTrackController(player, { pauseAtEnd = it }, store,
        { published += it }, { elapsed }, { wall })
    private fun advance(ms: Long) { elapsed += ms; wall += ms }

    @Test fun enableSavesAndCancelClears() {
        val controller = controller()
        controller.setEnabled(true)
        assertEquals(EotTarget("book", 0, elapsed, wall), store.target)
        assertTrue(pauseAtEnd)
        assertTrue(published.last())
        controller.setEnabled(false)
        assertNull(store.target)
        assertFalse(pauseAtEnd)
        assertFalse(published.last())
    }

    @Test fun activeTrackChangeUpdatesTargetAndBookChangeClears() {
        controller().setEnabled(true)
        advance(1000)
        player.change(index = 1)
        assertEquals(EotTarget("book", 1, elapsed, wall), store.target)
        assertTrue(pauseAtEnd)
        player.load(bookId = "other")
        assertNull(store.target)
        assertFalse(pauseAtEnd)
        assertFalse(published.last())
    }

    @Test fun playPauseTransitionsRefreshTimestamp() {
        controller().setEnabled(true)
        advance(1000)
        player.change(ready = true)
        assertEquals(elapsed, store.target?.updatedAtElapsedRealtimeMs)
        advance(1000)
        player.change(ready = false)
        assertEquals(elapsed, store.target?.updatedAtElapsedRealtimeMs)
        assertEquals(wall, store.target?.updatedAtWallTimeMs)
    }

    @Test fun endOfMediaItemPauseFiresAndClears() {
        controller().setEnabled(true)
        player.change(ready = true)
        player.change(ready = false, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertNull(store.target)
        assertFalse(pauseAtEnd)
        assertFalse(published.last())
        player.change(index = 1)
        assertFalse(pauseAtEnd)
    }

    @Test fun recreationRestoresMatchingItemAndPublishes() {
        val first = controller()
        first.setEnabled(true)
        first.release()
        advance(1000)
        controller()
        assertTrue(pauseAtEnd)
        assertTrue(published.last())
    }

    @Test fun restorationWaitsForMatchingPlaylist() {
        player.load(count = 0)
        store.target = EotTarget("book", 1, elapsed, wall)
        controller()
        assertFalse(pauseAtEnd)
        assertNotNull(store.target)
        player.load(index = 1)
        assertTrue(pauseAtEnd)
        assertTrue(published.last())
    }

    @Test fun mismatchedOrStaleOrRebootedRestorationClears() {
        for (target in listOf(EotTarget("other", 0, elapsed, wall), EotTarget("book", 2, elapsed, wall),
            EotTarget("book", 0, elapsed - EndOfTrackState.MAX_AGE_MS - 1,
                wall - EndOfTrackState.MAX_AGE_MS - 1), EotTarget("book", 0, elapsed, wall - 6000))) {
            store.target = target
            val controller = controller()
            assertNull(store.target)
            assertFalse(pauseAtEnd)
            assertFalse(published.last())
            controller.release()
        }
    }

    @Test fun clearingQueueClearsActiveTimerAndEmptyEnableDoesNothing() {
        val controller = controller()
        controller.setEnabled(true)
        player.load(count = 0)
        assertNull(store.target)
        assertFalse(pauseAtEnd)
        controller.setEnabled(true)
        assertNull(store.target)
        assertFalse(pauseAtEnd)
    }
}
