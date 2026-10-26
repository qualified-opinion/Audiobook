package com.janreins.audiobook

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.janreins.audiobook.data.FolderAccess
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Changing the library folder while a book from the old folder is still loaded (roadmap 1.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FolderSwitchTest {
    private val authority = "com.android.externalstorage.documents"
    private val oldFolder = DocumentsContract.buildTreeDocumentUri(authority, "primary:Old books")
    private val newFolder = DocumentsContract.buildTreeDocumentUri(authority, "primary:New books")
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    private fun file(tree: Uri, path: String) = DocumentsContract.buildDocumentUriUsingTree(tree, path)

    private fun book(tree: Uri, folder: String) = Audiobook(
        "folder:$folder", file(tree, "$folder/01.mp3"), "Book", "2 files",
        tracks = listOf(1, 2).map { AudioTrack(file(tree, "$folder/0$it.mp3"), "$folder/0$it.mp3", "0$it.mp3", "0$it", 0L, 0L) }
    )

    private fun granted() = resolver.persistedUriPermissions.map { it.uri }

    @Test fun fileInsideFolderBelongsToThatFolder() {
        assertEquals(FolderAccess.treeKey(oldFolder), FolderAccess.treeKey(file(oldFolder, "primary:Old books/a.mp3")))
        assertNotEquals(FolderAccess.treeKey(oldFolder), FolderAccess.treeKey(newFolder))
        assertNull(FolderAccess.treeKey(Uri.parse("content://media/external/audio/1")))
    }

    @Test fun oldFolderIsReleasedWhenNothingFromItIsLoaded() {
        assertEquals(listOf(oldFolder), FolderAccess.unusedTrees(listOf(oldFolder, newFolder), newFolder, emptyList()))
    }

    @Test fun oldFolderIsKeptWhileItsBookIsLoaded() {
        val playing = FolderAccess.filesOf(book(oldFolder, "primary:Old books/Dune"))
        assertEquals(emptyList<Uri>(), FolderAccess.unusedTrees(listOf(oldFolder, newFolder), newFolder, playing))
    }

    @Test fun libraryFolderIsNeverReleased() {
        assertEquals(emptyList<Uri>(), FolderAccess.unusedTrees(listOf(newFolder), newFolder, emptyList()))
    }

    @Test fun nonFolderGrantsAreLeftAlone() {
        val single = Uri.parse("content://$authority/document/primary%3Abackup.json")
        assertEquals(emptyList<Uri>(), FolderAccess.unusedTrees(listOf(single), newFolder, emptyList()))
    }

    @Test fun switchingWhilePlayingKeepsOldFolderUntilAnotherBookStarts() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        resolver.takePersistableUriPermission(oldFolder, flags)
        resolver.takePersistableUriPermission(newFolder, flags)
        val playing = book(oldFolder, "primary:Old books/Dune")

        // The folder is changed while Dune plays: its folder stays readable, and the user is told why.
        assertTrue(FolderAccess.releaseUnusedTrees(resolver, newFolder, playing))
        assertEquals(setOf(oldFolder, newFolder), granted().toSet())

        // A book from the new folder is started: the old folder is no longer needed.
        assertFalse(FolderAccess.releaseUnusedTrees(resolver, newFolder, book(newFolder, "primary:New books/Emma")))
        assertEquals(listOf(newFolder), granted())
    }

    @Test fun switchingWithNothingLoadedReleasesOldFolderAtOnce() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        resolver.takePersistableUriPermission(oldFolder, flags)
        resolver.takePersistableUriPermission(newFolder, flags)

        assertFalse(FolderAccess.releaseUnusedTrees(resolver, newFolder, null))
        assertEquals(listOf(newFolder), granted())
    }
}
