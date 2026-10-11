package com.janreins.audiobook

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.player.AudiobookPlayerManager
import com.janreins.audiobook.player.BookChapter
import com.janreins.audiobook.player.SessionPlayer
import com.janreins.audiobook.player.SessionSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerCompletionTest {
    private lateinit var context: Application
    private lateinit var prefs: PreferencesManager
    private lateinit var library: LibraryStateStore
    private lateinit var wrapped: SessionPlayerTest.FakePlayer
    private lateinit var sessionPlayer: SessionPlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var viewModel: MainViewModel
    private lateinit var book: Audiobook
    private lateinit var listener: Player.Listener

    // Inject a real local MediaController without changing the singleton's production API.
    private fun field(name: String) = AudiobookPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(null, value)
    @Suppress("UNCHECKED_CAST")
    private fun select(book: Audiobook?) {
        (field("_currentBook").get(null) as MutableStateFlow<Audiobook?>).value = book
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PreferencesManager(context)
        library = LibraryStateStore(context)
        wrapped = SessionPlayerTest.FakePlayer()
        sessionPlayer = SessionPlayer(wrapped, object : SessionSettings {
            override fun skipBackMs() = 15000L
            override fun skipForwardMs() = 30000L
            override fun smartRewindEnabled() = true
            override fun isFinished(bookId: String) = library.isFinished(bookId)
        }, { null })
        session = MediaSession.Builder(context, sessionPlayer).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        ShadowLooper.idleMainLooper()
        assertTrue(future.isDone)
        controller = future.get()
        set("controller", controller)
        set("appContext", null)
        set("prefsManager", null)
        set("libraryStateStore", null)
        set("controllerFuture", null)
        book = Audiobook("book", Uri.parse("content://book/0"), "Book", "book.mp3", 300000,
            tracks = (0 until 3).map { AudioTrack(Uri.parse("content://book/$it"), "$it", "$it.mp3", "Track $it", 100000, 0) })
        select(book)
        listener = field("listener").get(null) as Player.Listener
        controller.addListener(listener)
        viewModel = MainViewModel(context)
    }

    @After fun cleanup() {
        (field("positionTrackerJob").get(null) as Job?)?.cancel()
        controller.removeListener(listener)
        controller.release()
        session.release()
        sessionPlayer.release()
        set("controller", null)
        set("appContext", null)
        set("controllerFuture", null)
        select(null)
        ShadowLooper.idleMainLooper()
    }

    private fun playButtonRestarts() {
        wrapped.change(index = 2, position = 99000)
        library.setFinished(book.id, true)
        wrapped.calls.clear()
        viewModel.togglePlayPause(context)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("seek:0:0", "play:true"), wrapped.calls)
        assertEquals(0, wrapped.currentMediaItemIndex)
        assertEquals(0L, wrapped.currentPosition)
        assertTrue(wrapped.isPlaying)
        assertFalse(library.isFinished(book.id))
    }

    @Test fun playerScreenButtonRestartsInOnePress() = playButtonRestarts()
    @Test fun miniPlayerButtonRestartsInOnePress() = playButtonRestarts()

    @Test fun markLoadedFinishedPrefersLiveDurationAndUnloadsWithoutSeek() {
        book = book.copy(tracks = book.tracks.map { it.copy(durationMs = 70000) })
        select(book)
        wrapped.change(index = 2, position = 50000, ready = true)
        wrapped.calls.clear()
        viewModel.markFinished(book)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("play:false", "stop", "clear"), wrapped.calls)
        assertEquals(0, wrapped.mediaItemCount)
        assertFalse(wrapped.playWhenReady)
        assertEquals(book, AudiobookPlayerManager.currentBook.value)
        assertEquals(2, AudiobookPlayerManager.currentTrackIndex.value)
        assertEquals(100000L, AudiobookPlayerManager.currentPositionMs.value)
        assertEquals(BookProgress(2, 100000), prefs.getBookProgress(book.id))
        assertTrue(library.isFinished(book.id))
    }

    @Test fun markUnknownDurationFinishedThenAppPlayReloadsFromStartInOnePress() {
        book = book.copy(durationMs = 0, tracks = book.tracks.map { it.copy(durationMs = 0) })
        select(book)
        wrapped.change(index = 1, position = 50000, ready = true)
        viewModel.markFinished(book)
        ShadowLooper.idleMainLooper()
        assertEquals(BookProgress(2, 0), prefs.getBookProgress(book.id))
        assertTrue(library.isFinished(book.id))
        assertEquals(0, wrapped.mediaItemCount)
        wrapped.calls.clear()
        viewModel.togglePlayPause(context)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("load:0:0", "prepare", "play:true"), wrapped.calls)
        assertEquals(0, wrapped.currentMediaItemIndex)
        assertEquals(0L, wrapped.currentPosition)
        assertTrue(wrapped.isPlaying)
        assertFalse(library.isFinished(book.id))
    }

    @Test fun markUnplayedAfterQueueClearedUpdatesVisibleAndStoredProgress() {
        wrapped.change(index = 2, position = 50000)
        viewModel.markFinished(book)
        ShadowLooper.idleMainLooper()
        viewModel.markUnplayed(book)
        ShadowLooper.idleMainLooper()
        assertEquals(BookProgress(), prefs.getBookProgress(book.id))
        assertEquals(0, AudiobookPlayerManager.currentTrackIndex.value)
        assertEquals(0L, AudiobookPlayerManager.currentPositionMs.value)
        assertFalse(library.isFinished(book.id))
    }

    @Test fun bookmarkOneSecondBeforeTrackEndSeeksExactlyThere() {
        AudiobookPlayerManager.playBook(context, book, customStartPosMs = 99000, customTrackIndex = 1)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("seek:1:99000", "play:true"), wrapped.calls)
        assertEquals(99000L, wrapped.currentPosition)
    }

    @Test fun bookmarkAtFinishedBookEndOverridesSessionRestart() {
        wrapped.change(index = 2, position = 100000, playback = Player.STATE_ENDED)
        library.setFinished(book.id, true)
        wrapped.calls.clear()
        AudiobookPlayerManager.playBook(context, book, customStartPosMs = 99000, customTrackIndex = 2)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("seek:2:99000", "play:true"), wrapped.calls)
        assertEquals(2, wrapped.currentMediaItemIndex)
        assertEquals(99000L, wrapped.currentPosition)
        assertFalse(library.isFinished(book.id))
    }

    @Test fun chapterAtFinishedBookEndKeepsExactStart() {
        wrapped.change(index = 2, position = 100000, playback = Player.STATE_ENDED)
        library.setFinished(book.id, true)
        wrapped.calls.clear()
        viewModel.jumpToChapter(BookChapter(2, 99000, 100000, "Closing", true))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("seek:2:99000", "play:true"), wrapped.calls)
        assertEquals(99000L, wrapped.currentPosition)
        assertFalse(library.isFinished(book.id))
    }
}
