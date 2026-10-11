package com.janreins.audiobook

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.player.ResumeButtonGate
import com.janreins.audiobook.player.ResumeButtonReceiver
import com.janreins.audiobook.player.ResumePermissions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeButtonReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun intent(key: Int) = Intent(Intent.ACTION_MEDIA_BUTTON)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key))

    @Test fun gateTruthTable() {
        val snapshot = resumeSnapshot()
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
            assertFalse(ResumeButtonGate.allows(key, snapshot, BookProgress(), false, { true }, { true }))
        }
        assertFalse(ResumeButtonGate.allows(KeyEvent.KEYCODE_MEDIA_PLAY, null, BookProgress(), false, { true }, { true }))
        assertFalse(ResumeButtonGate.allows(KeyEvent.KEYCODE_MEDIA_PLAY, snapshot, BookProgress(), false, { false }, { true }))
        assertFalse(ResumeButtonGate.allows(KeyEvent.KEYCODE_MEDIA_PLAY, snapshot, BookProgress(), false, { true }, { false }))
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertTrue(ResumeButtonGate.allows(key, snapshot, BookProgress(), false, { true }, { true }))
        }
    }

    @Test fun permissionBoundaryRequiresMatchingAuthorityAndDocumentSubtree() {
        assertTrue(ResumePermissions.contains("books", "library", "books", "library/one.mp3"))
        assertTrue(ResumePermissions.contains("books", "library", "books", "library/sub/one.mp3"))
        assertFalse(ResumePermissions.contains("other", "library", "books", "library/one.mp3"))
        assertFalse(ResumePermissions.contains("books", "library", "books", "library2/one.mp3"))
        assertFalse(ResumePermissions.contains("books", "library", "books", "other/one.mp3"))
        assertTrue(ResumePermissions.contains("books", "primary:", "books", "primary:one.mp3"))
        assertFalse(ResumePermissions.covers(Uri.parse("file:///library"), Uri.parse("file:///library/one.mp3")))
    }

    @Test fun receiverChecksPersistedPermissionAndTheActualColdStartTarget() {
        val snapshot = resumeSnapshot()
        val prefs = PreferencesManager(context)
        val library = LibraryStateStore(context)
        val receiver = ResumeButtonReceiver()
        val store = ResumeSnapshotStore(context)
        store.clear()
        assertFalse(receiver.shouldStartForegroundService(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)))
        store.write(snapshot)
        assertFalse(receiver.shouldStartForegroundService(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)))
        context.contentResolver.takePersistableUriPermission(Uri.parse(snapshot.folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(receiver.shouldStartForegroundService(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)))
        val outside = snapshot.copy(tracks = snapshot.tracks.mapIndexed { i, t ->
            if (i == 1) t.copy(uri = "content://books/document/elsewhere%2Ftwo.mp3") else t
        })
        store.write(outside)
        prefs.saveBookProgress(snapshot.bookId, BookProgress(0, 9500))
        assertFalse(receiver.shouldStartForegroundService(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)))
        prefs.saveBookProgress(snapshot.bookId, BookProgress(1, 10000))
        library.setFinished(snapshot.bookId, true)
        assertTrue(receiver.shouldStartForegroundService(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)))
    }

    @Test fun falseGateOnReceiveStartsNoService() {
        val receiver = ResumeButtonReceiver()
        val store = ResumeSnapshotStore(context)
        store.clear()
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT)) {
            receiver.onReceive(context, intent(key))
            assertNull(shadowOf(context).nextStartedService)
        }
        store.write(resumeSnapshot())
        receiver.onReceive(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY)) // no folder grant
        assertNull(shadowOf(context).nextStartedService)
        context.contentResolver.takePersistableUriPermission(Uri.parse(resumeSnapshot().folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        receiver.onReceive(context, intent(KeyEvent.KEYCODE_MEDIA_PAUSE)) // playable snapshot, non-play key
        assertNull(shadowOf(context).nextStartedService)
        val outside = resumeSnapshot().copy(tracks = resumeSnapshot().tracks.map {
            it.copy(uri = "content://books/document/outside%2Fa.mp3")
        })
        store.write(outside)
        receiver.onReceive(context, intent(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertNull(shadowOf(context).nextStartedService)
    }
}
