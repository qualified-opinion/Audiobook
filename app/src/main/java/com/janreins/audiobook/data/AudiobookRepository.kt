package com.janreins.audiobook.data

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.ScannedAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Scans and loads audio files from the selected Storage Access Framework (SAF) folder,
 * including any sub-folders, into a clean, flat list of audiobooks.
 * Runs completely locally on-device without any internet connection.
 */
class AudiobookRepository(private val context: Context) {

    private val durationCache = DurationCache(context)

    // Common audiobook & audio file extensions supported by Android's media player
    private val supportedExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "wav", "wma", "opus"
    )

    /**
     * Scans the chosen directory URI and any nested sub-folders.
     * Returns naturally sorted books grouped by their immediate containing folder.
     */
    data class LibraryScan(val books: List<Audiobook>, val succeeded: Boolean)

    suspend fun loadAudiobooksFromFolder(folderUri: Uri): List<Audiobook> = loadLibrary(folderUri).books

    suspend fun loadLibrary(folderUri: Uri): LibraryScan = withContext(Dispatchers.IO) {
        val files = readAudioFiles(folderUri)
        LibraryScan(BookGrouping.group(files ?: emptyList()), files != null)
    }

    suspend fun scanAudioFiles(folderUri: Uri): List<ScannedAudioFile> = withContext(Dispatchers.IO) {
        readAudioFiles(folderUri) ?: emptyList()
    }

    /** null means an unreadable root; a genuinely empty folder is a successful scan. */
    private fun readAudioFiles(folderUri: Uri): List<ScannedAudioFile>? {
        val results = mutableListOf<ScannedAudioFile>()
        val seen = mutableSetOf<String>()
        val rootDirectory = try {
            DocumentFile.fromTreeUri(context, folderUri)?.takeIf { it.exists() && it.isDirectory }
        } catch (_: Exception) { null } ?: return null
        try {
            scanDirectoryRecursively(rootDirectory.uri, rootDirectory.name, 0, 3, results, seen)
        } catch (_: Exception) { return null }

        durationCache.save(seen)
        ChapterRepository.prune(seen.mapTo(mutableSetOf()) { ChapterRepository.cacheKey(it) })
        return results
    }

    /**
     * Scans one folder and recurses into its sub-folders. Name, type, size and date of every child
     * come from a single query per folder (the old DocumentFile calls made about five queries per
     * file, which was slow on SD cards and cloud folders). Unreadable sub-folders and files are skipped.
     */
    private fun scanDirectoryRecursively(
        directoryUri: Uri,
        directoryName: String?,
        currentDepth: Int,
        maxDepth: Int,
        results: MutableList<ScannedAudioFile>,
        seen: MutableSet<String>
    ) {
        val children = try {
            // A null cursor (rather than an empty one) means the folder couldn't be read, so a failed
            // root is distinguishable from an empty folder.
            queryChildren(directoryUri) ?: throw java.io.IOException("Cannot read audiobook folder")
        } catch (e: Exception) {
            if (currentDepth == 0) throw e
            emptyList()
        }

        val directoryId = documentId(directoryUri)
        val members = mutableListOf<ScannedAudioFile>()
        val images = mutableMapOf<String, FolderEntry>()
        for (child in children) {
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, child.documentId)
                if (child.isDirectory) {
                    if (currentDepth < maxDepth) {
                        scanDirectoryRecursively(uri, child.name, currentDepth + 1, maxDepth, results, seen)
                    }
                    continue
                }
                if (!child.isFile) continue
                if (currentDepth > 0 && child.name != null && CoverSources.pickFolderCover(listOf(child.name)) != null) {
                    images.putIfAbsent(child.name, child)
                }
                if (child.isAudio(supportedExtensions)) {
                    val key = DurationCache.key(child.documentId, child.sizeBytes, child.lastModified)
                    seen.add(key)
                    val duration = durationCache.get(key) ?: extractDuration(uri).also {
                        durationCache.put(key, it)
                    }
                    members.add(ScannedAudioFile(uri, child.documentId, child.name ?: "Untitled Audio",
                        child.sizeBytes, child.lastModified, directoryId, directoryName, currentDepth == 0, duration))
                }
            } catch (_: Exception) {
                // Ignore single file or sub-folder read failures so the rest continues uninterrupted
            }
        }
        val cover = CoverSources.pickFolderCover(images.keys.toList())?.let { images[it] }
        val coverUri = cover?.let { DocumentsContract.buildDocumentUriUsingTree(directoryUri, it.documentId) }
        val coverKey = cover?.let { DurationCache.key(it.documentId, it.sizeBytes, it.lastModified) }
        results.addAll(members.map {
            it.copy(folderCoverUri = coverUri, folderCoverKey = coverKey)
        })
    }

    /** One query for all children of a folder; null when the folder can't be read. */
    private fun queryChildren(directoryUri: Uri): List<FolderEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri,
            DocumentsContract.getDocumentId(directoryUri))
        val cursor = context.contentResolver.query(childrenUri, FolderEntry.PROJECTION, null, null, null)
            ?: return null
        return cursor.use {
            val id = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            if (id < 0) return null
            val name = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modified = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val entries = mutableListOf<FolderEntry>()
            while (it.moveToNext()) {
                val documentId = it.getString(id) ?: continue
                entries.add(FolderEntry(documentId, it.stringOrNull(name), it.stringOrNull(mime),
                    it.longOrZero(size), it.longOrZero(modified)))
            }
            entries
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)
    private fun Cursor.longOrZero(index: Int): Long = if (index < 0 || isNull(index)) 0L else getLong(index)

    private fun documentId(uri: Uri): String = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) { uri.toString() }

    /**
     * Attempts to read the track duration using Android's MediaMetadataRetriever.
     * Fails gracefully to 0 if the file format header is damaged or unreadable.
     */
    private fun extractDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {
        /**
         * Helper to format milliseconds into HH:MM:SS or MM:SS format.
         */
        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "00:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return if (hours > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
    }
}
