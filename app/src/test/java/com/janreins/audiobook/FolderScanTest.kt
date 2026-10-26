package com.janreins.audiobook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.janreins.audiobook.data.AudiobookRepository
import com.janreins.audiobook.data.FolderEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** A folder tree served by a SAF-shaped provider that counts how often it is asked for what. */
class FolderTreeProvider : ContentProvider() {
    private val dir = DocumentsContract.Document.MIME_TYPE_DIR
    // document id -> (mime type, children)
    val tree = mapOf(
        "root" to (dir to listOf("root/single.mp3", "root/Book A", "root/Book B")),
        "root/single.mp3" to ("audio/mpeg" to emptyList()),
        "root/Book A" to (dir to listOf("root/Book A/02.mp3", "root/Book A/01.mp3", "root/Book A/cover.jpg",
            "root/Book A/notes.txt")),
        "root/Book A/02.mp3" to ("audio/mpeg" to emptyList()),
        "root/Book A/01.mp3" to ("audio/mpeg" to emptyList()),
        "root/Book A/cover.jpg" to ("image/jpeg" to emptyList()),
        "root/Book A/notes.txt" to ("text/plain" to emptyList()),
        "root/Book B" to (dir to listOf("root/Book B/Disc 1")),
        "root/Book B/Disc 1" to (dir to listOf("root/Book B/Disc 1/part.m4b")),
        "root/Book B/Disc 1/part.m4b" to ("application/octet-stream" to emptyList()),
    )
    val childQueries = mutableListOf<String>()
    val documentQueries = mutableListOf<String>()
    private lateinit var audio: File

    override fun onCreate(): Boolean {
        audio = File(requireNotNull(context).cacheDir, "scan-fixture.mp3")
        audio.writeBytes(javaClass.getResourceAsStream("/fixtures/fx.mp3")!!.use { it.readBytes() })
        return true
    }
    override fun getType(uri: Uri) = tree[DocumentsContract.getDocumentId(uri)]?.first
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns)
        fun add(id: String) {
            val mime = tree.getValue(id).first
            cursor.addRow(columns.map<String, Any?> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id.substringAfterLast('/')
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                DocumentsContract.Document.COLUMN_SIZE -> if (mime == dir) null else 1000L + id.length
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 789L
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            } }.toTypedArray())
        }
        val id = DocumentsContract.getDocumentId(uri)
        if (uri.lastPathSegment == "children") {
            childQueries += id
            tree[id]?.second?.forEach(::add)
        } else {
            documentQueries += id
            if (id in tree) add(id)
        }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderScanTest {
    private lateinit var provider: FolderTreeProvider
    private val folder = DocumentsContract.buildTreeDocumentUri("scanbooks", "root")

    @Before fun setup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        provider = FolderTreeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "scanbooks" })
        ShadowContentResolver.registerProviderInternal("scanbooks", provider)
    }

    private fun scan() = runBlocking {
        AudiobookRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext()).loadLibrary(folder)
    }

    @Test fun oneChildrenQueryPerFolderAndNoQueriesPerFile() {
        val scan = scan()
        assertTrue(scan.succeeded)
        assertEquals(listOf("root", "root/Book A", "root/Book B", "root/Book B/Disc 1"), provider.childQueries)
        // Only the chosen folder itself is looked up (does it exist, is it a folder); never single files.
        assertTrue(provider.documentQueries.toString(), provider.documentQueries.all { it == "root" })
    }

    @Test fun groupsBooksAndKeepsFileDetailsFromTheFolderQuery() {
        val books = scan().books.associateBy { it.title }
        assertEquals(setOf("single", "Book A", "Disc 1"), books.keys)

        val bookA = books.getValue("Book A")
        assertEquals(listOf("01.mp3", "02.mp3"), bookA.tracks.map { it.fileName })
        assertEquals("folder:root/Book A", bookA.id)
        val first = bookA.tracks.first()
        assertEquals("root/Book A/01.mp3", first.documentId)
        assertEquals(1000L + "root/Book A/01.mp3".length, first.sizeBytes)
        assertEquals(789L, first.lastModified)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(folder, "root/Book A/01.mp3"), first.uri)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(folder, "root/Book A/cover.jpg"), bookA.coverUri)
        assertEquals("root/Book A/cover.jpg|${1000L + "root/Book A/cover.jpg".length}|789", bookA.coverKey)

        assertEquals("file:root/single.mp3", books.getValue("single").id)
        assertNull(books.getValue("single").coverUri)
        // Recognised by its extension although the provider calls it octet-stream.
        assertEquals(listOf("part.m4b"), books.getValue("Disc 1").tracks.map { it.fileName })
    }

    @Test fun folderEntryRules() {
        val audioExtensions = setOf("mp3", "m4b")
        fun entry(name: String?, mime: String?) = FolderEntry("id", name, mime, 0, 0)
        assertTrue(entry("x", DocumentsContract.Document.MIME_TYPE_DIR).isDirectory)
        assertFalse(entry("x", DocumentsContract.Document.MIME_TYPE_DIR).isAudio(audioExtensions))
        assertTrue(entry("talk.ogg", "audio/ogg").isAudio(audioExtensions))
        assertTrue(entry("Part.M4B", "application/octet-stream").isAudio(audioExtensions))
        assertFalse(entry("notes.txt", "text/plain").isAudio(audioExtensions))
        assertFalse(entry("one.mp3", null).isAudio(audioExtensions)) // no type: not a file, as DocumentFile
        assertFalse(entry(null, "application/octet-stream").isAudio(audioExtensions))
    }
}
