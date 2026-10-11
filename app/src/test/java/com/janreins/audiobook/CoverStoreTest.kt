package com.janreins.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.CoverModel
import com.janreins.audiobook.data.CoverStore
import com.janreins.audiobook.data.CoverThumbnail
import com.janreins.audiobook.data.DurationCache
import com.janreins.audiobook.data.coverModel
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var cache: File
    private val model = CoverModel("book", "Dune", Uri.parse("content://books/first"), "first|50|1", null, null)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cache = File(context.cacheDir, "covers")
        cache.deleteRecursively()
    }

    private fun image(color: Int, width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun folderModel(): CoverModel {
        val file = temporaryFolder.newFile("cover.jpg")
        file.writeBytes(image(Color.BLUE))
        return model.copy(folderCoverUri = Uri.fromFile(file), folderCoverKey = "cover|100|1")
    }

    private fun assertColor(file: File, red: Int, blue: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            val pixel = bitmap.getPixel(0, 0)
            assertEquals(red.toDouble(), Color.red(pixel).toDouble(), 10.0)
            assertEquals(blue.toDouble(), Color.blue(pixel).toDouble(), 10.0)
        } finally { bitmap.recycle() }
    }

    @Test fun embeddedWinsOverFolderAndRunsOffMainThread() = runBlocking {
        var calls = 0
        val bytes = image(Color.RED)
        val store = CoverStore(context) {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            calls++
            bytes
        }
        val source = folderModel()
        val file = requireNotNull(store.thumbnailFile(source))
        assertColor(file, 255, 0)
        assertEquals(file, store.thumbnailFile(source))
        assertEquals(1, calls)
    }

    @Test fun folderIsUsedWithoutEmbeddedArt() = runBlocking {
        val store = CoverStore(context) { null }
        assertColor(requireNotNull(store.thumbnailFile(folderModel())), 0, 255)
    }

    @Test fun missingArtIsRememberedAcrossStoreInstances() = runBlocking {
        var calls = 0
        val reader: (Uri) -> ByteArray? = { calls++; null }
        assertNull(CoverStore(context, reader).thumbnailFile(model))
        assertEquals(1, cache.listFiles().orEmpty().count { it.extension == "none" })
        assertNull(CoverStore(context, reader).thumbnailFile(model))
        assertEquals(1, calls)
    }

    @Test fun changedFolderTimestampResolvesAgain() = runBlocking {
        var calls = 0
        val store = CoverStore(context) { calls++; null }
        val source = folderModel()
        val first = requireNotNull(store.thumbnailFile(source))
        val folderFile = File(requireNotNull(source.folderCoverUri).path!!)
        folderFile.writeBytes(image(Color.RED))
        val updated = source.copy(folderCoverKey = "cover|100|2")
        assertNotEquals(CoverStore.cacheKey(source), CoverStore.cacheKey(updated))
        val second = requireNotNull(store.thumbnailFile(updated))
        assertNotEquals(first, second)
        assertColor(second, 255, 0)
        assertEquals(2, calls)
    }

    @Test fun changedAudioKeyInvalidatesMissingArtMarker() = runBlocking {
        var calls = 0
        val store = CoverStore(context) { calls++; null }
        assertNull(store.thumbnailFile(model))
        val updated = model.copy(firstTrackKey = "first|50|2")
        assertNotEquals(CoverStore.cacheKey(model), CoverStore.cacheKey(updated))
        assertNull(store.thumbnailFile(updated))
        assertEquals(2, calls)
    }

    @Test fun largeEmbeddedAndFolderImagesAreReducedTo512() = runBlocking {
        val bytes = image(Color.RED, 1600, 1200)
        val embedded = requireNotNull(CoverStore(context) { bytes }.thumbnailFile(model))
        val file = temporaryFolder.newFile("large.jpg").apply { writeBytes(bytes) }
        val folder = requireNotNull(CoverStore(context) { null }.thumbnailFile(model.copy(
            folderCoverUri = Uri.fromFile(file), folderCoverKey = "large|1|1")))
        for (thumbnail in listOf(embedded, folder)) {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(thumbnail.path))
            try {
                assertEquals(512, maxOf(bitmap.width, bitmap.height))
                assertEquals(384, minOf(bitmap.width, bitmap.height))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun folderIoFailureDoesNotWriteMarkerAndCanRetry() = runBlocking {
        val file = File(temporaryFolder.root, "missing.jpg")
        val source = model.copy(folderCoverUri = Uri.fromFile(file), folderCoverKey = "missing|1|1")
        val store = CoverStore(context) { null }
        assertNull(store.thumbnailFile(source))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        file.writeBytes(image(Color.BLUE))
        assertNotNull(store.thumbnailFile(source))
    }

    @Test fun trimCapsThumbnailsAndMarkersSeparatelyAndKeepsRecentFiles() = runBlocking {
        assertTrue(cache.mkdirs())
        val now = System.currentTimeMillis()
        repeat(CoverStore.MAX_THUMBNAILS + 1) { index ->
            File(cache, "old$index.jpg").apply { writeText("x"); setLastModified(index + 1L) }
        }
        repeat(5) { index -> File(cache, "recent$index.jpg").apply { writeText("x"); setLastModified(now) } }
        repeat(CoverStore.MAX_MARKERS + 1) { index ->
            File(cache, "old$index.none").apply { writeText(""); setLastModified(index + 1L) }
        }
        assertNull(CoverStore(context) { null }.thumbnailFile(model))
        val files = cache.listFiles().orEmpty()
        assertEquals(CoverStore.TRIM_THUMBNAILS_TO, files.count { it.extension == "jpg" })
        assertEquals(CoverStore.TRIM_MARKERS_TO, files.count { it.extension == "none" })
        assertFalse(File(cache, "old0.jpg").exists())
        assertTrue(File(cache, "old${CoverStore.MAX_THUMBNAILS}.jpg").exists())
        assertTrue((0 until 5).all { File(cache, "recent$it.jpg").exists() })
        // The marker just written for this model is recent, so it survives.
        assertNull(CoverStore(context) { error("must not read again") }.thumbnailFile(model))
    }

    @Test fun cacheHitRefreshesTimestampForLeastRecentlyUsedTrim() = runBlocking {
        val store = CoverStore(context) { image(Color.RED) }
        val file = requireNotNull(store.thumbnailFile(model))
        assertTrue(file.setLastModified(1000L))
        assertEquals(file, store.thumbnailFile(model))
        assertTrue(file.lastModified() > 1000L)
    }

    @Test fun failedEmbeddedReadWritesNoMarkerAndRetriesOnNextLaunch() = runBlocking {
        var calls = 0
        val store = CoverStore(context) { calls++; throw java.io.IOException("transient") }
        assertNull(store.thumbnailFile(model))
        assertTrue(cache.listFiles().orEmpty().none { it.extension == "none" })
        // Same process: not re-read on every request.
        assertNull(store.thumbnailFile(model))
        assertEquals(1, calls)
        // Next launch (new store): read again, and a successful "no picture" result is then remembered.
        assertNull(CoverStore(context) { calls++; null }.thumbnailFile(model))
        assertEquals(2, calls)
        assertEquals(1, cache.listFiles().orEmpty().count { it.extension == "none" })
    }

    @Test fun failedEmbeddedReadUsesFolderCoverInMemoryWithoutCachingIt() = runBlocking {
        var calls = 0
        val store = CoverStore(context) { calls++; throw java.io.IOException("unsupported") }
        val source = folderModel()
        val thumbnail = store.thumbnail(source) as CoverThumbnail.InMemory
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(thumbnail.jpeg, 0, thumbnail.jpeg.size))
        assertEquals(255.0, Color.blue(bitmap.getPixel(0, 0)).toDouble(), 10.0)
        assertNull(store.thumbnailFile(source))
        assertTrue(cache.listFiles().orEmpty().none { it.extension == "jpg" || it.extension == "none" })
        assertEquals(1, calls)
        // Next launch: the embedded read succeeds and that art is cached.
        val file = requireNotNull(CoverStore(context) { image(Color.RED) }.thumbnailFile(source))
        assertColor(file, 255, 0)
    }

    @Test fun failedEmbeddedReadsEvictLeastRecentlyUsed() = runBlocking {
        val calls = mutableMapOf<Uri, Int>()
        val store = CoverStore(context) { uri ->
            calls[uri] = (calls[uri] ?: 0) + 1
            throw java.io.IOException("unsupported")
        }
        val folder = folderModel()
        val sources = (0..CoverStore.MAX_FAILED_READS).map { index ->
            folder.copy(firstTrackUri = Uri.parse("content://books/$index"), firstTrackKey = "$index|50|1")
        }
        repeat(CoverStore.MAX_FAILED_READS) { index ->
            assertTrue(store.thumbnail(sources[index]) is CoverThumbnail.InMemory)
        }
        val first = store.thumbnail(sources[0]) as CoverThumbnail.InMemory
        assertEquals(1, calls[sources[0].firstTrackUri])
        assertTrue(store.thumbnail(sources.last()) is CoverThumbnail.InMemory)
        val retained = store.thumbnail(sources[0]) as CoverThumbnail.InMemory
        assertSame(first.jpeg, retained.jpeg)
        assertEquals(1, calls[sources[0].firstTrackUri])
        assertTrue(store.thumbnail(sources[1]) is CoverThumbnail.InMemory)
        assertEquals(2, calls[sources[1].firstTrackUri])
    }

    @Test fun hitsOlderThanFiveMinutesAreRefreshedButRecentOnesAreNot() = runBlocking {
        val store = CoverStore(context) { image(Color.RED) }
        val file = requireNotNull(store.thumbnailFile(model))
        val twoMinutesAgo = System.currentTimeMillis() - 2 * 60_000L
        assertTrue(file.setLastModified(twoMinutesAgo))
        store.thumbnailFile(model)
        assertEquals(twoMinutesAgo / 1000, file.lastModified() / 1000)
        val sixMinutesAgo = System.currentTimeMillis() - 6 * 60_000L
        assertTrue(file.setLastModified(sixMinutesAgo))
        store.thumbnailFile(model)
        assertTrue(file.lastModified() > sixMinutesAgo + 60_000L)
    }

    @Test fun modelUsesFirstTrackSizeAndTimestampWithLegacyFallback() {
        val book = Audiobook("file:id", model.firstTrackUri, "Dune", "Dune.m4b", sizeBytes = 90)
        assertEquals(DurationCache.key(book.id, 90, 0), book.coverModel().firstTrackKey)
        val track = AudioTrack(Uri.parse("content://books/track"), "track", "1.mp3", "1", 0, 50, 123)
        val grouped = book.copy(tracks = listOf(track), coverUri = Uri.parse("content://books/cover"), coverKey = "cover|2|3")
        assertEquals(track.uri, grouped.coverModel().firstTrackUri)
        assertEquals(DurationCache.key("track", 50, 123), grouped.coverModel().firstTrackKey)
        assertEquals("v1|track|50|123|cover|2|3", CoverStore.cacheKey(grouped.coverModel()))
    }
}
