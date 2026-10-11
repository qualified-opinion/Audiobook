package com.janreins.audiobook.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.player.ChapterIndex
import com.janreins.audiobook.player.ChapterMetadata
import com.janreins.audiobook.player.RawChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared by the UI and playback service; all file and extraction work runs on IO. */
@OptIn(UnstableApi::class)
object ChapterRepository {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Any()
    private val requested = mutableMapOf<String, List<String>>()
    private val mutableIndexes = MutableStateFlow<Map<String, ChapterIndex>>(emptyMap())
    val indexes = mutableIndexes.asStateFlow()

    fun init(context: Context) { this.context = context.applicationContext }
    fun key(documentId: String, sizeBytes: Long, lastModified: Long) =
        cacheKey(DurationCache.key(documentId, sizeBytes, lastModified))

    /**
     * v2: QuickTime chapter tracks are read (v1 used MetadataRetriever's default extractor flags, which skip
     * them), so v1 entries are ignored and pruned on the next scan.
     */
    fun cacheKey(durationKey: String) = "v2|$durationKey"

    private fun file(key: String): File = File(context.filesDir, "chapters/${hash(key)}.json")
    private fun hash(key: String) = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun read(key: String): List<RawChapter>? = synchronized(cacheLock) {
        try {
            val array = JSONArray(AtomicFile(file(key)).openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                RawChapter(entry.getLong("startMs"), entry.getLong("endMs"),
                    if (entry.isNull("title")) null else entry.getString("title"), entry.optBoolean("hidden"))
            }
        } catch (_: Exception) { null }
    }

    fun write(key: String, chapters: List<RawChapter>): Unit = synchronized(cacheLock) {
        val target = file(key)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            val array = JSONArray()
            chapters.forEach { array.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)
                .put("title", it.title ?: JSONObject.NULL).put("hidden", it.hidden)) }
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun prune(seenKeys: Set<String>) {
        if (!::context.isInitialized) return
        synchronized(cacheLock) {
            val names = seenKeys.mapTo(mutableSetOf()) { "${hash(it)}.json" }
            File(context.filesDir, "chapters").listFiles()?.forEach {
                if (it.name.removeSuffix(".bak").removeSuffix(".new") !in names) it.delete()
            }
        }
        // A rescan may have replaced a file, so permit rebuilding its book index.
        synchronized(this) { requested.clear() }
    }

    @Synchronized fun ensureLoaded(book: Audiobook) {
        if (!::context.isInitialized) return
        val tracks = book.tracks.ifEmpty { listOf(AudioTrack(book.uri, book.id.removePrefix("file:"),
            book.fileName, book.title, book.durationMs, book.sizeBytes, book.lastModified)) }
        val keys = tracks.map { key(it.documentId, it.sizeBytes, it.lastModified) }
        if (requested[book.id] == keys) return
        requested[book.id] = keys
        val titles = tracks.map { it.title }
        mutableIndexes.update { it + (book.id to ChapterIndex.build(tracks.map { null to it.durationMs }, titles)) }
        scope.launch {
            var failed = false
            val raw = tracks.mapIndexed { i, track ->
                val chapters = read(keys[i]) ?: try {
                    extract(track).also { write(keys[i], it) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true; emptyList() }
                chapters to track.durationMs
            }
            val index = ChapterIndex.build(raw, titles)
            synchronized(this@ChapterRepository) {
                if (requested[book.id] == keys) {
                    mutableIndexes.update { it + (book.id to index) }
                    if (failed) requested.remove(book.id) // Retry a failed extraction when the book is opened again.
                }
            }
        }
    }

    private suspend fun extract(track: AudioTrack): List<RawChapter> = extractTwoPass(
        fast = { readChapters(fastRetriever(context, track.uri)) },
        full = { readChapters(newRetriever(context, track.uri)) }
    )

    // The full read builds the whole sample table (~3.7 MB/hour of audio, ~150 MB transient heap for a
    // 40 h single-file M4B). ffmpeg-made M4Bs carry Nero chpl by default and resolve on the fast pass.
    // QuickTime-only or chapterless files pay one extra header-only moov read, once per file, then cached.
    // When both chpl and QuickTime exist, chpl wins (the same source in practice). Exactly 255 chapters
    // may mean truncation of the commonly 8-bit chpl count, so try the full read in that case too.
    internal suspend fun extractTwoPass(
        fast: suspend () -> List<RawChapter>,
        full: suspend () -> List<RawChapter>
    ): List<RawChapter> {
        val chapters = fast()
        return if (chapters.isEmpty() || chapters.size == 255) full().ifEmpty { chapters } else chapters
    }

    private suspend fun readChapters(retriever: MetadataRetriever): List<RawChapter> {
        try {
            // Formats within an adaptive group share chapters; use one audio format per group.
            return chaptersOf(retriever.retrieveTrackGroups().await())
        } finally { retriever.close() }
    }

    /** Default MP4 flags include FLAG_OMIT_TRACK_SAMPLE_TABLE: Nero chpl is read, QuickTime tracks are skipped. */
    internal fun fastRetriever(context: Context, uri: Uri): MetadataRetriever =
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build()

    /**
     * Full pass for QuickTime chapter tracks, used only when the fast pass finds zero or exactly 255 chapters.
     * Reading the full sample table still costs memory for long QuickTime-only M4Bs (~3.7 MB/hour), but
     * only once per file, lazily, and the result is cached.
     * Safe off the main thread: the retriever runs on Media3's shared worker thread, not the caller's looper.
     */
    internal fun newRetriever(context: Context, uri: Uri): MetadataRetriever =
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context,
                DefaultExtractorsFactory().setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA)))
            .build()

    /** Chapters of the first audio track group (see [newRetriever]). */
    internal fun chaptersOf(groups: TrackGroupArray): List<RawChapter> = (0 until groups.length).flatMap { i ->
        val group = groups[i]
        if (group.type == C.TRACK_TYPE_AUDIO) ChapterMetadata.map(group.getFormat(0).metadata) else emptyList()
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        Futures.addCallback(this, object : FutureCallback<T> {
            override fun onSuccess(result: T) { continuation.resume(result) }
            override fun onFailure(t: Throwable) { continuation.resumeWithException(t) }
        }, MoreExecutors.directExecutor())
        continuation.invokeOnCancellation { cancel(true) }
    }
}
