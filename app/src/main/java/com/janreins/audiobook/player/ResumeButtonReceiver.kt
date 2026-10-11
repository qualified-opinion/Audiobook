package com.janreins.audiobook.player

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.janreins.audiobook.data.FolderAccess
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.ResumeSnapshot
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.data.model.BookProgress

/** Pure document-id boundary check: a sibling such as library2 is outside library. */
object ResumePermissions {
    fun contains(treeAuthority: String?, treeId: String, trackAuthority: String?, documentId: String): Boolean =
        treeAuthority != null && treeAuthority == trackAuthority &&
            (documentId == treeId || documentId.startsWith(if (treeId.endsWith('/') || treeId.endsWith(':')) treeId else "$treeId/"))

    fun covers(tree: Uri, track: Uri): Boolean = try {
        tree.scheme == "content" && track.scheme == "content" && DocumentsContract.isTreeUri(tree) &&
            contains(tree.authority, DocumentsContract.getTreeDocumentId(tree),
                track.authority, DocumentsContract.getDocumentId(track))
    } catch (_: Exception) { false }
}

object ResumeButtonGate {
    fun isPlay(key: Int) = key == KeyEvent.KEYCODE_MEDIA_PLAY ||
        key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_HEADSETHOOK

    fun allows(key: Int, snapshot: ResumeSnapshot?, saved: BookProgress, finished: Boolean,
               folderAccess: (String) -> Boolean, trackAccess: (String) -> Boolean): Boolean {
        if (!isPlay(key) || snapshot == null || !folderAccess(snapshot.folderUri)) return false
        val target = CompletionRules.coldStart(snapshot.tracks.size, { snapshot.tracks[it].durationMs }, saved, finished)
        return trackAccess(snapshot.tracks[target.index].uri)
    }
}

@OptIn(UnstableApi::class)
class ResumeButtonReceiver : MediaButtonReceiver() {
    // Verified in Media3 1.11.1 bytecode: a false gate returns from onReceive without starting anything.
    public override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
        val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || !ResumeButtonGate.isPlay(event.keyCode)) return false
        return try {
            val snapshot = ResumeSnapshotStore(context).read() ?: return false
            val prefs = PreferencesManager(context)
            ResumeButtonGate.allows(event.keyCode, snapshot, prefs.getBookProgress(snapshot.bookId),
                LibraryStateStore(context).isFinished(snapshot.bookId),
                { FolderAccess.hasPersistedReadAccess(context.contentResolver, it.toUri()) },
                { uri -> context.contentResolver.persistedUriPermissions.any {
                    it.isReadPermission && ResumePermissions.covers(it.uri, uri.toUri())
                } })
        } catch (_: Exception) { false }
    }
}
