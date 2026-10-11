package com.janreins.audiobook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.janreins.audiobook.data.model.Audiobook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class CoverModel(
    val bookId: String,
    val title: String,
    val firstTrackUri: Uri,
    val firstTrackKey: String,
    val folderCoverUri: Uri?,
    val folderCoverKey: String?
)

sealed interface CoverThumbnail {
    /** Persisted thumbnail in the cover cache. */
    data class Cached(val file: File) : CoverThumbnail
    /** Shown for this process only (folder cover while the embedded read is failing). */
    class InMemory(val jpeg: ByteArray) : CoverThumbnail
}

fun Audiobook.coverModel(): CoverModel {
    val first = tracks.firstOrNull()
    return CoverModel(id, title, first?.uri ?: uri,
        DurationCache.key(first?.documentId ?: id, first?.sizeBytes ?: sizeBytes, first?.lastModified ?: 0L),
        coverUri, coverKey)
}

/**
 * [embeddedArtReader] returns null only when the file was read and has no embedded picture; it throws
 * when the file could not be read, so a transient failure never records "no art".
 */
class CoverStore(
    context: Context,
    private val embeddedArtReader: (Uri) -> ByteArray? = { uri -> readEmbeddedArt(context, uri) }
) {
    private val context = context.applicationContext
    private val cacheDirectory = File(context.cacheDir, "covers")
    /**
     * Keys whose embedded read failed in this process, with the folder thumbnail (JPEG) used meanwhile.
     * Nothing about them is persisted, so the next launch reads the audio file again; within this process
     * they are not re-read on every scroll.
     */
    private val failedEmbeddedReads = object : LinkedHashMap<String, ByteArray?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray?>) = size > MAX_FAILED_READS
    }

    /** Cached thumbnails only; see [thumbnail] for the in-memory fallback. */
    suspend fun thumbnailFile(model: CoverModel): File? = (thumbnail(model) as? CoverThumbnail.Cached)?.file

    suspend fun thumbnail(model: CoverModel): CoverThumbnail? = withContext(Dispatchers.IO) {
        // Fast path without the lock: cache hits and remembered "no art" never wait behind an extraction.
        val key = hashOf(cacheKey(model))
        File(cacheDirectory, "$key.jpg").takeIf { it.isFile }?.let { return@withContext CoverThumbnail.Cached(touched(it)) }
        if (File(cacheDirectory, "$key.none").isFile) return@withContext null
        synchronized(failedEmbeddedReads) { failedEmbeddedReads[key] }?.let { return@withContext CoverThumbnail.InMemory(it) }
        // Serialize cache writes and pruning, including simultaneous requests for the same book.
        cacheMutex.withLock {
            var temp: File? = null
            var bitmap: Bitmap? = null
            try {
                check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
                val hash = key
                val image = File(cacheDirectory, "$hash.jpg")
                val none = File(cacheDirectory, "$hash.none")
                if (image.isFile) return@withLock CoverThumbnail.Cached(touched(image))
                if (none.isFile) return@withLock null

                var embeddedReadFailed = synchronized(failedEmbeddedReads) { hash in failedEmbeddedReads }
                if (!embeddedReadFailed) {
                    val embedded = try {
                        embeddedArtReader(model.firstTrackUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        synchronized(failedEmbeddedReads) { failedEmbeddedReads[hash] = null }
                        embeddedReadFailed = true
                        null
                    }
                    bitmap = embedded?.let { decodeDownsampled(it, 512) }
                }
                if (bitmap == null) {
                    bitmap = model.folderCoverUri?.let { decodeDownsampled(it, 512) }
                }
                if (bitmap == null) {
                    // Only a successful read that found nothing is remembered. A failed audio read or an
                    // unreadable folder stream (which throws) stays retryable, without a marker.
                    if (embeddedReadFailed) return@withLock null
                    temp = File.createTempFile(hash, ".tmp", cacheDirectory)
                    check(temp.renameTo(none))
                    trimCache()
                    return@withLock null
                }
                val resolved = bitmap
                if (embeddedReadFailed) {
                    // The embedded art may still exist: show the folder cover now, but don't cache it.
                    val jpeg = java.io.ByteArrayOutputStream().use {
                        check(resolved.compress(Bitmap.CompressFormat.JPEG, 85, it)); it.toByteArray()
                    }
                    synchronized(failedEmbeddedReads) { failedEmbeddedReads[hash] = jpeg }
                    return@withLock CoverThumbnail.InMemory(jpeg)
                }
                val temporary = File.createTempFile(hash, ".tmp", cacheDirectory)
                temp = temporary
                temporary.outputStream().use { check(resolved.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                check(temporary.renameTo(image))
                trimCache()
                CoverThumbnail.Cached(image)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                bitmap?.recycle()
                temp?.delete()
            }
        }
    }

    fun decodeDownsampled(bytes: ByteArray, maxPx: Int): Bitmap? = BitmapDecoding.decodeDownsampled(bytes, maxPx)
    fun decodeDownsampled(uri: Uri, maxPx: Int): Bitmap? = BitmapDecoding.decodeDownsampled(context, uri, maxPx)

    /**
     * Cache hits refresh the timestamp (when older than 5 minutes, inside the 10-minute protection window),
     * so trimming drops the least recently used.
     */
    private fun touched(file: File): File {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_INTERVAL_MS) file.setLastModified(now)
        return file
    }

    /**
     * Thumbnails (~30-60 KB) and the empty "no art" markers are capped separately. Files used in the last
     * few minutes are never deleted, so a path just handed to Coil stays valid.
     */
    private fun trimCache() {
        val files = cacheDirectory.listFiles()?.filter { it.isFile } ?: return
        val recent = System.currentTimeMillis() - KEEP_RECENT_MS
        fun trim(entries: List<File>, max: Int, target: Int) {
            if (entries.size <= max) return
            entries.filter { it.lastModified() < recent }.sortedBy { it.lastModified() }
                .take(entries.size - target).forEach { it.delete() }
        }
        trim(files.filter { it.extension == "jpg" }, MAX_THUMBNAILS, TRIM_THUMBNAILS_TO)
        trim(files.filter { it.extension == "none" }, MAX_MARKERS, TRIM_MARKERS_TO)
    }

    companion object {
        private val cacheMutex = Mutex()
        internal const val MAX_FAILED_READS = 64
        internal const val MAX_THUMBNAILS = 600
        internal const val TRIM_THUMBNAILS_TO = 500
        internal const val MAX_MARKERS = 2000
        internal const val TRIM_MARKERS_TO = 1500
        private const val KEEP_RECENT_MS = 10 * 60_000L
        private const val TOUCH_INTERVAL_MS = 5 * 60_000L
        fun cacheKey(model: CoverModel): String = "v1|${model.firstTrackKey}|${model.folderCoverKey ?: "-"}"

        private fun hashOf(key: String): String = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Throws if the file cannot be opened or parsed; null means it has no embedded picture. */
        private fun readEmbeddedArt(context: Context, uri: Uri): ByteArray? {
            var retriever: MediaMetadataRetriever? = null
            return try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                try { retriever?.release() } catch (_: Exception) { /* Ignore cleanup errors. */ }
            }
        }
    }
}

/** Bounds are read first, so large artwork is never decoded at its original resolution. */
internal object BitmapDecoding {
    fun decodeDownsampled(bytes: ByteArray, maxPx: Int): Bitmap? = decode(maxPx) { options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun decodeDownsampled(context: Context, uri: Uri, maxPx: Int): Bitmap? = decode(maxPx) { options ->
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open cover: $uri")
        stream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private inline fun decode(maxPx: Int, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        require(maxPx > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (longest.toLong() > maxPx.toLong() * 2 * sample) sample *= 2
        val bitmap = read(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val decodedLongest = maxOf(bitmap.width, bitmap.height)
        if (decodedLongest <= maxPx) return bitmap
        val scale = maxPx.toFloat() / decodedLongest
        return try {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } finally {
            bitmap.recycle()
        }
    }
}
