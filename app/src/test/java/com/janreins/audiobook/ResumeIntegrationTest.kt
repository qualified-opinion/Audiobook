package com.janreins.audiobook

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.janreins.audiobook.data.AudiobookRepository
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.data.toBook
import com.janreins.audiobook.data.toResumeSnapshot
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.player.AudiobookPlayerManager
import com.janreins.audiobook.player.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** A real SAF-shaped provider serves the same four-second MP3 fixtures as the cleared-queue harness. */
class ResumeFixtureProvider : ContentProvider() {
    var rootReadable = false
    var exposeBook = false
    private lateinit var audio: File
    override fun onCreate(): Boolean {
        audio = File(requireNotNull(context).cacheDir, "resume-fixture.mp3")
        audio.writeBytes(javaClass.getResourceAsStream("/fixtures/fx.mp3")!!.use { it.readBytes() })
        return true
    }
    override fun getType(uri: Uri) = if (uri.lastPathSegment == "library")
        DocumentsContract.Document.MIME_TYPE_DIR else "audio/mpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns)
        fun add(id: String, directory: Boolean = false) {
            cursor.addRow(columns.map<String, Any?> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id.substringAfterLast('/')
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "audio/mpeg"
                DocumentsContract.Document.COLUMN_SIZE -> audio.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 456L
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            } }.toTypedArray())
        }
        if (uri.lastPathSegment == "children") {
            if (!rootReadable) return null
            if (exposeBook) add("library/one.mp3")
        } else {
            val id = DocumentsContract.getDocumentId(uri)
            if (id == "library") {
                if (!rootReadable) return null
                add(id, directory = true)
            } else add(id)
        }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeIntegrationTest {
    private lateinit var context: Application
    private lateinit var prefs: PreferencesManager
    private lateinit var library: LibraryStateStore
    private lateinit var store: ResumeSnapshotStore
    private lateinit var provider: ResumeFixtureProvider
    private lateinit var serviceController: ServiceController<PlaybackService>
    private lateinit var session: MediaSession
    private var headset: MediaController? = null
    private val folder = Uri.parse("content://books/tree/library")
    private lateinit var book: Audiobook

    private fun field(name: String) = AudiobookPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(null, value)
    private fun managerController() = field("controller").get(null) as MediaController?
    @Suppress("UNCHECKED_CAST")
    private fun resetManager() {
        (field("positionTrackerJob").get(null) as Job?)?.cancel()
        (field("sleepTimerJob").get(null) as Job?)?.cancel()
        managerController()?.let {
            it.removeListener(field("listener").get(null) as Player.Listener)
            it.release()
        }
        (field("controllerFuture").get(null) as ListenableFuture<*>?)?.cancel(false)
        for (name in listOf("controller", "controllerFuture", "appContext", "prefsManager", "libraryStateStore",
            "positionTrackerJob", "sleepTimerJob")) set(name, null)
        (field("knownBooks").get(null) as MutableMap<String, Audiobook>).clear()
        (field("pendingActions").get(null) as MutableList<*>).clear()
        (field("_currentBook").get(null) as MutableStateFlow<Audiobook?>).value = null
        (field("_isPlaying").get(null) as MutableStateFlow<Boolean>).value = false
    }
    private fun settle() = repeat(40) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
    private fun await(description: String, condition: () -> Boolean) {
        repeat(600) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out: $description")
    }

    @Before fun setup() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        resetManager()
        context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PreferencesManager(context)
        library = LibraryStateStore(context)
        store = ResumeSnapshotStore(context)
        // Four-second fixture durations; saved positions below two seconds avoid end tolerance.
        val snapshot = resumeSnapshot().copy(durationMs = 8000, coverUri = null, coverKey = null,
            tracks = resumeSnapshot().tracks.map { it.copy(durationMs = 4000) })
        book = snapshot.toBook()
        store.write(snapshot)
        prefs.saveFolderUri(folder.toString())
        prefs.saveBookProgress(book.id, BookProgress(1, 1000))
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider = ResumeFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "books" })
        ShadowContentResolver.registerProviderInternal("books", provider)
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController.get()
        session = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as MediaSession
        val binder = service.onBind(Intent("androidx.media3.session.MediaSessionService"))
        shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, PlaybackService::class.java), binder)
    }

    @After fun cleanup() {
        headset?.release()
        resetManager()
        serviceController.destroy()
        ShadowLooper.idleMainLooper()
    }

    /**
     * Robolectric records bindService as a "started" service, and the launch MediaController binding
     * predates this PR. Anything else (a startService/startForegroundService) would be a real start.
     */
    private fun startedServicesOtherThanControllerBinding(): List<Intent> {
        val started = mutableListOf<Intent>()
        while (true) started += shadowOf(context).nextStartedService ?: break
        return started.filter { it.action != "androidx.media3.session.MediaSessionService" }
    }

    private fun assertNoServiceStartOrForeground() {
        assertEquals(emptyList<Intent>(), startedServicesOtherThanControllerBinding())
        assertNull(shadowOf(serviceController.get()).lastForegroundNotification)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    private fun callback(): MediaSession.Callback {
        val impl = MediaSession::class.java.getDeclaredField("impl").apply { isAccessible = true }.get(session)
        return impl.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(impl) as MediaSession.Callback
    }

    private fun connectHeadset(): MediaController {
        val future = MediaController.Builder(context, session.token).buildAsync()
        await("headset connection") { future.isDone }
        return future.get().also { headset = it }
    }

    @Test fun coldControllerPlayLoadsSavedBookAndTheOnlyWriterSavesOnPause() {
        prefs.savePlaybackSpeed(1.5f)
        val controller = connectHeadset()
        assertNull(field("appContext").get(null))
        assertEquals(0, session.player.mediaItemCount)
        controller.play()
        // initialize was posted, never called inline from the callback or by waiting on a future.
        assertNull(field("appContext").get(null))
        await("resume and asynchronous writer connection") {
            managerController() != null && session.player.mediaItemCount == 2 && session.player.playWhenReady
        }
        assertEquals(1.5f, session.player.playbackParameters.speed, 0f)
        assertEquals(book.id, AudiobookPlayerManager.currentBook.value?.id)
        assertEquals(1, session.player.currentMediaItemIndex)
        assertTrue(session.player.currentPosition >= 1000)
        // Seek through the external controller, then pause: the manager must map the resumed media id.
        controller.seekTo(1, 1500)
        settle()
        controller.pause()
        settle()
        assertEquals(1, prefs.getBookProgress(book.id).trackIndex)
        assertTrue(prefs.getBookProgress(book.id).positionMs >= 1500)
        assertEquals(session.player.currentPosition, prefs.getBookProgress(book.id).positionMs)
    }

    @Test fun nonPlayCommandsOnAnEmptySessionLoadNothingAndStartNothing() {
        val controller = connectHeadset()
        val before = prefs.getBookProgress(book.id)
        controller.pause(); controller.stop(); controller.seekToNext(); controller.seekToNextMediaItem()
        settle()
        assertEquals(0, session.player.mediaItemCount)
        assertFalse(session.player.playWhenReady)
        assertNull(field("appContext").get(null))
        assertEquals(before, prefs.getBookProgress(book.id))
        assertNoServiceStartOrForeground()
    }

    @Test fun readOnlyServiceCallbackDoesNotInitializeOrChangePreferencesSpeedOrQueue() {
        connectHeadset()
        prefs.savePlaybackSpeed(1.5f)
        val preferences = context.getSharedPreferences("audiobook_player_preferences", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val snapshotBefore = store.read()
        val result = callback().onPlaybackResumption(session, session.connectedControllers.first(), false)
        await("read-only callback") { result.isDone }
        assertEquals(1, result.get().startIndex)
        assertEquals(1000L, result.get().startPositionMs)
        settle()
        assertNull(field("appContext").get(null))
        assertEquals(before, preferences.all)
        assertEquals(snapshotBefore, store.read())
        assertEquals(1f, session.player.playbackParameters.speed, 0f)
        assertEquals(0, session.player.mediaItemCount)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun coldFinishedBookStartsAtZeroAndClearsFinished() {
        prefs.saveBookProgress(book.id, BookProgress(1, 4000))
        library.setFinished(book.id, true)
        var initial: BookProgress? = null
        session.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem != null && initial == null) initial = BookProgress(session.player.currentMediaItemIndex, session.player.currentPosition)
            }
        })
        connectHeadset().play()
        await("finished restart") { session.player.isPlaying && managerController() != null && !library.isFinished(book.id) }
        assertEquals(BookProgress(), initial)
        assertEquals(0, session.player.currentMediaItemIndex)
    }

    @Test fun launchRestoresOnlyUiWithoutStartingServiceOrPostingNotification() {
        prefs.saveBookProgress(book.id, BookProgress(1, 4000))
        library.setFinished(book.id, true)
        val viewModel = MainViewModel(context)
        await("launch controller binding") { managerController() != null }
        assertEquals(book, viewModel.currentPlayingBook.value)
        assertEquals(1, viewModel.currentTrackIndex.value)
        assertEquals(4000L, viewModel.currentPositionMs.value)
        assertEquals(2, viewModel.trackCount.value)
        assertEquals(0, session.player.mediaItemCount)
        assertFalse(session.player.playWhenReady)
        settle()
        assertNoServiceStartOrForeground()
        assertNotNull(store.read()) // the unreadable-root scan must keep the snapshot
    }

    @Test fun revokedPermissionPreventsLaunchRestore() {
        context.contentResolver.releasePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        MainViewModel(context)
        await("controller binding with revoked permission") { managerController() != null }
        assertNull(AudiobookPlayerManager.currentBook.value)
        assertEquals(0, session.player.mediaItemCount)
        assertNoServiceStartOrForeground()
    }

    @Test fun scanRefreshSwapsFreshBookAndSnapshotThenMissingBookClearsBoth() {
        AudiobookPlayerManager.initialize(context)
        await("manager binding") { managerController() != null }
        val fresh = book.copy(title = "Fresh", coverKey = "new-cover",
            tracks = book.tracks.map { it.copy(durationMs = 5000) })
        AudiobookPlayerManager.reconcileLibrary(listOf(fresh), scanSucceeded = true)
        assertEquals(fresh, AudiobookPlayerManager.currentBook.value)
        assertEquals(fresh.toResumeSnapshot(folder.toString()), store.read())
        assertEquals(5000L, AudiobookPlayerManager.durationMs.value)
        AudiobookPlayerManager.reconcileLibrary(emptyList(), scanSucceeded = false)
        assertEquals(fresh, AudiobookPlayerManager.currentBook.value)
        assertNotNull(store.read())
        AudiobookPlayerManager.reconcileLibrary(emptyList(), scanSucceeded = true)
        assertNull(AudiobookPlayerManager.currentBook.value)
        assertNull(store.read())
    }

    @Test fun realScanDistinguishesUnavailableRootFromSuccessfulEmptyFolder() {
        val repository = AudiobookRepository(context)
        val failed = runBlocking { repository.loadLibrary(folder) }
        assertFalse(failed.succeeded)
        assertTrue(failed.books.isEmpty())
        provider.rootReadable = true
        val empty = runBlocking { repository.loadLibrary(folder) }
        assertTrue(empty.succeeded)
        assertTrue(empty.books.isEmpty())
        val viewModel = MainViewModel(context)
        await("successful empty scan removes restored book") { viewModel.currentPlayingBook.value == null && store.read() == null }
        assertEquals(0, session.player.mediaItemCount)
    }

    @Test fun viewModelScanSwapsMatchingBookAndRefreshesChangedMetadata() {
        // A root file is a single-track book. Match the scanner's stable id without using a player.
        val stale = book.copy(id = "file:library/one.mp3", title = "Stale",
            tracks = listOf(book.tracks.first()), durationMs = 4000)
        store.write(stale.toResumeSnapshot(folder.toString()))
        prefs.saveBookProgress(stale.id, BookProgress(0, 1000))
        provider.rootReadable = true
        provider.exposeBook = true
        val viewModel = MainViewModel(context)
        await("scan replaces stale snapshot") {
            viewModel.audiobooks.value.isNotEmpty() && viewModel.currentPlayingBook.value?.title != "Stale"
        }
        val fresh = viewModel.audiobooks.value.single()
        assertEquals(stale.id, fresh.id)
        assertEquals(fresh, viewModel.currentPlayingBook.value)
        assertEquals(fresh.toResumeSnapshot(folder.toString()), store.read())
        assertEquals(0, session.player.mediaItemCount)
    }

    @Test fun successfulScanDoesNotClearTheBookWhileItsQueueIsLoaded() {
        connectHeadset().play()
        await("loaded queue") { session.player.mediaItemCount == 2 && managerController() != null }
        AudiobookPlayerManager.reconcileLibrary(emptyList(), scanSucceeded = true)
        assertNull(store.read())
        assertEquals(book.id, AudiobookPlayerManager.currentBook.value?.id)
        assertEquals(2, session.player.mediaItemCount)
    }

    @Test fun selectingAnotherFolderClearsSnapshot() {
        val viewModel = MainViewModel(context)
        viewModel.onFolderSelected(context, Uri.parse("content://books/tree/other"))
        assertNull(store.read())
    }

    @Test fun launchSurfacesAndConsumesUnavailableFlag() {
        prefs.setResumeUnavailable()
        val viewModel = MainViewModel(context)
        assertEquals("Last book unavailable", viewModel.folderMessage.value)
        assertFalse(prefs.consumeResumeUnavailable())
    }
}
