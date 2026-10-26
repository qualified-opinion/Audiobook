package com.janreins.audiobook.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.janreins.audiobook.data.model.Audiobook

object FolderAccess {
    fun hasReadAccess(folderUri: String, persisted: List<Pair<String, Boolean>>): Boolean =
        persisted.any { (uri, read) -> uri == folderUri && read }

    fun hasPersistedReadAccess(contentResolver: ContentResolver, uri: Uri): Boolean =
        hasReadAccess(uri.toString(), contentResolver.persistedUriPermissions.map {
            it.uri.toString() to it.isReadPermission
        })

    /** The folder tree a grant or a file inside it belongs to; null for anything that is not a tree URI. */
    fun treeKey(uri: Uri): String? = runCatching {
        if (DocumentsContract.isTreeUri(uri)) "${uri.authority}/${DocumentsContract.getTreeDocumentId(uri)}" else null
    }.getOrNull()

    /**
     * Folder grants nothing needs any more: not the library folder, and not a folder that [inUse]
     * (the loaded book's files) still plays from. Releasing that one would break its next file.
     */
    fun unusedTrees(granted: List<Uri>, library: Uri?, inUse: List<Uri>): List<Uri> {
        val keep = (inUse + listOfNotNull(library)).mapNotNullTo(mutableSetOf(), ::treeKey)
        return granted.filter { uri -> treeKey(uri)?.let { it !in keep } == true }
    }

    fun filesOf(book: Audiobook): List<Uri> = listOf(book.uri) + book.tracks.map { it.uri } + listOfNotNull(book.coverUri)

    /**
     * Gives up old folder grants. Returns true when a folder other than [library] was kept because
     * [loadedBook] still plays from it; it is released later, once another book is started.
     */
    fun releaseUnusedTrees(contentResolver: ContentResolver, library: Uri?, loadedBook: Audiobook?): Boolean {
        val granted = contentResolver.persistedUriPermissions
        val unused = unusedTrees(granted.map { it.uri }, library, loadedBook?.let(::filesOf).orEmpty()).toSet()
        granted.filter { it.uri in unused }.forEach { permission ->
            val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try {
                contentResolver.releasePersistableUriPermission(permission.uri, flags)
            } catch (e: SecurityException) {
                Log.w("FolderAccess", "Could not release old folder permission", e)
            }
        }
        val libraryKey = library?.let(::treeKey)
        return granted.any { it.uri !in unused && treeKey(it.uri).let { key -> key != null && key != libraryKey } }
    }
}
