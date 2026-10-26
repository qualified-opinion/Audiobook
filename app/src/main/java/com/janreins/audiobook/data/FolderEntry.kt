package com.janreins.audiobook.data

import android.provider.DocumentsContract
import java.util.Locale

/** One child of a scanned folder, read from a single row of the folder's children query. */
data class FolderEntry(
    val documentId: String,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    /** Same rule as DocumentFile.isFile: anything with a type that isn't a folder. */
    val isFile: Boolean get() = !isDirectory && !mimeType.isNullOrEmpty()

    /** An audio type, or a known audio extension (some providers report audio as octet-stream). */
    fun isAudio(extensions: Set<String>): Boolean {
        if (!isFile) return false
        if (mimeType!!.startsWith("audio/")) return true
        val extension = name?.lowercase(Locale.ROOT)?.substringAfterLast('.', "") ?: return false
        return extension in extensions
    }

    companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
