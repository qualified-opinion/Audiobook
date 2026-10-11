package com.janreins.audiobook

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.player.AudiobookPlayerManager
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.player.PlaybackResumption
import com.janreins.audiobook.player.PlaybackPositions
import com.janreins.audiobook.player.SessionPlayer
import com.janreins.audiobook.player.SessionSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import com.janreins.audiobook.data.model.BookProgress
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
/** Regressions from the #11 review, driven through a real ExoPlayer, MediaSession and controllers. */
class ClearedQueueRegressionTest {
    private lateinit var context: Application
    private lateinit var prefs: PreferencesManager
    private lateinit var library: LibraryStateStore
    private lateinit var exo: ExoPlayer
    private lateinit var sessionPlayer: SessionPlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var headset: MediaController
    private lateinit var viewModel: MainViewModel
    private lateinit var book: Audiobook
    private lateinit var listener: Player.Listener
    private val errors = mutableListOf<Throwable>()

    private fun field(name: String) = AudiobookPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(null, value)
    @Suppress("UNCHECKED_CAST")
    private fun select(book: Audiobook?) { (field("_currentBook").get(null) as MutableStateFlow<Audiobook?>).value = book }
    private fun settle() = repeat(60) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun s() = "exo[items=${exo.mediaItemCount} idx=${exo.currentMediaItemIndex} pos=${exo.currentPosition} state=${exo.playbackState} pwr=${exo.playWhenReady}] " +
        "ui[track=${AudiobookPlayerManager.currentTrackIndex.value} pos=${AudiobookPlayerManager.currentPositionMs.value} book=${AudiobookPlayerManager.currentBook.value?.id}] " +
        "saved=${prefs.getBookProgress(book.id)} finished=${library.isFinished(book.id)}"

    @Before fun setup() {
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PreferencesManager(context); library = LibraryStateStore(context)
        exo = ExoPlayer.Builder(context).build()
        sessionPlayer = SessionPlayer(exo, object : SessionSettings {
            override fun skipBackMs() = 15000L
            override fun skipForwardMs() = 30000L
            override fun smartRewindEnabled() = true
            override fun isFinished(bookId: String) = library.isFinished(bookId)
        }, { null })
        val resumption = PlaybackResumption({ ResumeSnapshotStore(context).read(deleteInvalid = it) },
            prefs::getBookProgress, library::isFinished, { true }, { true }, prefs::setResumeUnavailable,
            java.util.concurrent.Executor { it.run() })
        session = MediaSession.Builder(context, sessionPlayer).setCallback(object : MediaSession.Callback {
            override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean) = resumption.resume(isForPlayback)
        }).build()
        val f = MediaController.Builder(context, session.token).buildAsync(); settle(); controller = f.get()
        val h = MediaController.Builder(context, session.token).buildAsync(); settle(); headset = h.get()
        set("controller", controller); set("appContext", null); set("prefsManager", null)
        set("libraryStateStore", null); set("controllerFuture", null)
        val bytes = javaClass.getResourceAsStream("/fixtures/fx.mp3")!!.use { it.readBytes() }
        val uris = (0 until 3).map { i -> Uri.fromFile(File(context.cacheDir, "t$i.mp3").apply { writeBytes(bytes) }) }
        book = Audiobook("book", uris[0], "Book", "t0.mp3", 12000,
            tracks = (0 until 3).map { AudioTrack(uris[it], "$it", "t$it.mp3", "Track $it", 4000, bytes.size.toLong()) })
        select(book)
        listener = field("listener").get(null) as Player.Listener
        controller.addListener(listener)
        viewModel = MainViewModel(context)
        prefs.saveFolderUri("content://books/tree/library")
    }

    @After fun cleanup() {
        (field("positionTrackerJob").get(null) as Job?)?.cancel()
        controller.removeListener(listener); headset.release(); controller.release(); session.release(); exo.release()
        set("controller", null); set("appContext", null); set("controllerFuture", null); select(null); settle()
    }

    private fun loadAndMarkFinished(): BookProgress {
        AudiobookPlayerManager.playBook(context, book, customStartPosMs = 1000, customTrackIndex = 1); settle()
        AudiobookPlayerManager.pause(); settle()
        viewModel.markFinished(book); settle()
        assertEquals(s(), 0, exo.mediaItemCount)
        assertTrue(s(), library.isFinished(book.id))
        return prefs.getBookProgress(book.id)
    }

    @Test fun headsetPlayAfterMarkFinishedThenUnplayedKeepsItUnplayed() {
        loadAndMarkFinished()
        viewModel.markUnplayed(book); settle()
        assertFalse(s(), library.isFinished(book.id))
        headset.play(); settle()
        assertFalse(s(), library.isFinished(book.id))
        assertEquals(s(), 0, prefs.getBookProgress(book.id).trackIndex)
        assertTrue(s(), prefs.getBookProgress(book.id).positionMs < 2000)
        assertEquals(s(), 3, exo.mediaItemCount)
        assertTrue(s(), exo.playWhenReady)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun skipAndSeekOnClearedQueueAreIgnored() {
        val stored = loadAndMarkFinished()
        AudiobookPlayerManager.skip(-15000); settle()
        AudiobookPlayerManager.seekTo(500); settle()
        assertEquals(s(), stored, prefs.getBookProgress(book.id))
        assertEquals(s(), stored.trackIndex, AudiobookPlayerManager.currentTrackIndex.value)
        assertEquals(s(), stored.positionMs, AudiobookPlayerManager.currentPositionMs.value)
        assertTrue(s(), library.isFinished(book.id))
        viewModel.togglePlayPause(context); settle()
        assertEquals(s(), 0, exo.currentMediaItemIndex)
        assertFalse(s(), library.isFinished(book.id))
    }

    @Test fun headsetPlayOnClearedQueueRestartsFinishedBookInOnePress() {
        loadAndMarkFinished()
        headset.play(); settle()
        assertEquals(s(), 3, exo.mediaItemCount)
        assertEquals(s(), 0, exo.currentMediaItemIndex)
        assertTrue(s(), exo.currentPosition < 2000)
        assertTrue(s(), exo.playWhenReady)
        assertFalse(s(), library.isFinished(book.id))
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun neverPlayedBookWithShortFirstTrackStartsOnTrackOne() {
        book = book.copy(tracks = book.tracks.mapIndexed { i, t -> if (i == 0) t.copy(durationMs = 1500) else t })
        select(null)
        AudiobookPlayerManager.playBook(context, book); settle()
        assertEquals(s(), 0, exo.currentMediaItemIndex)
        assertEquals(s(), 0, AudiobookPlayerManager.currentTrackIndex.value)
    }
}
