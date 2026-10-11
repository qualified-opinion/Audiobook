package com.janreins.audiobook.data

import android.content.Context
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
            scanDirectoryRecursively(rootDirectory, 0, 3, results, seen)
        } catch (_: Exception) { return null }

        durationCache.save(seen)
        ChapterRepository.prune(seen.mapTo(mutableSetOf()) { ChapterRepository.cacheKey(it) })
        return results
    }

    /**
     * Helper method to scan files in a directory and recurse into sub-folders.
     * Handles edge cases gently: empty sub-folders, permission issues, or non-audio items.
     */
    private fun scanDirectoryRecursively(
        directory: DocumentFile,
        currentDepth: Int,
        maxDepth: Int,
        results: MutableList<ScannedAudioFile>,
        seen: MutableSet<String>
    ) {
        val files = try {
            // DocumentFile.listFiles hides failed reads as empty lists. Query directly so a failed
            // root is distinguishable from an empty folder, while unreadable sub-folders are skipped.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory.uri,
                DocumentsContract.getDocumentId(directory.uri))
            val cursor = context.contentResolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?: throw java.io.IOException("Cannot read audiobook folder")
            cursor.use {
                val files = mutableListOf<DocumentFile>()
                while (it.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(directory.uri, it.getString(0))
                    DocumentFile.fromSingleUri(context, uri)?.let(files::add)
                }
                files.toTypedArray()
            }
        } catch (e: Exception) {
            if (currentDepth == 0) throw e
            emptyArray()
        }

        val members = mutableListOf<ScannedAudioFile>()
        val images = mutableMapOf<String, DocumentFile>()
        for (file in files) {
            try {
                val name = file.name
                val isFile = file.isFile
                if (currentDepth > 0 && isFile && name != null &&
                    CoverSources.pickFolderCover(listOf(name)) != null) {
                    images.putIfAbsent(name, file)
                }
                if (isFile && isAudioFile(file)) {
                    val fileName = name ?: "Untitled Audio"
                    val size = file.length()
                    val modified = file.lastModified()
                    val id = documentId(file.uri)
                    val key = DurationCache.key(id, size, modified)
                    seen.add(key)
                    val duration = durationCache.get(key) ?: extractDuration(file.uri).also {
                        durationCache.put(key, it)
                    }
                    members.add(ScannedAudioFile(file.uri, id, fileName, size, modified,
                        documentId(directory.uri), directory.name, currentDepth == 0, duration))
                } else if (file.isDirectory && currentDepth < maxDepth) {
                    // Dive into the sub-folder to look for more audio files
                    scanDirectoryRecursively(
                        directory = file,
                        currentDepth = currentDepth + 1,
                        maxDepth = maxDepth,
                        results = results,
                        seen = seen
                    )
                }
            } catch (e: Exception) {
                // Ignore single file or sub-folder read failures so the rest continues uninterrupted
            }
        }
        val cover = CoverSources.pickFolderCover(images.keys.toList())?.let { images[it] }
        val coverKey = try {
            cover?.let { DurationCache.key(documentId(it.uri), it.length(), it.lastModified()) }
        } catch (_: Exception) { null }
        results.addAll(members.map {
            it.copy(folderCoverUri = cover?.uri, folderCoverKey = coverKey)
        })
    }

    /**
     * Checks whether a DocumentFile is a supported audio file based on MIME type or extension.
     */
    private fun isAudioFile(file: DocumentFile): Boolean {
        val mime = file.type
        if (mime != null && mime.startsWith("audio/")) {
            return true
        }
        val name = file.name?.lowercase(Locale.ROOT) ?: return false
        val extension = name.substringAfterLast('.', "")
        return extension in supportedExtensions
    }

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
