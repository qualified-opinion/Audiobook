package com.janreins.audiobook

import com.janreins.audiobook.data.ResumeSnapshot
import com.janreins.audiobook.data.ResumeSnapshotStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun resumeSnapshot() = ResumeSnapshot("book", "Book", "one.mp3",
    "content://books/tree/library/document/library%2Fone.mp3", 20000, 123, 456,
    "content://books/tree/library", listOf(
        ResumeSnapshot.Track("content://books/tree/library/document/library%2Fone.mp3", "library/one.mp3", "one.mp3", "One", 10000, 60, 456),
        ResumeSnapshot.Track("content://books/tree/library/document/library%2Ftwo.mp3", "library/two.mp3", "two.mp3", "Two", 10000, 63, 457)),
    "content://books/tree/library/document/library%2Fcover.jpg", "cover-key", "00:20")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun roundTripIncludesEveryBookAndTrackField() {
        val snapshot = resumeSnapshot()
        assertEquals(snapshot, ResumeSnapshot.fromJson(snapshot.toJson()))
    }

    @Test fun missingOptionalFieldsAndExtraV1FieldsAreAccepted() {
        val json = JSONObject(resumeSnapshot().toJson())
        json.remove("coverUri"); json.remove("coverKey"); json.remove("formattedDuration")
        json.put("futureField", true)
        assertEquals(resumeSnapshot().copy(coverUri = null, coverKey = null, formattedDuration = ""),
            ResumeSnapshot.fromJson(json.toString()))
    }

    @Test fun garbagePartialAndUnknownVersionAreRejectedAndDeleted() {
        val directory = temp.newFolder()
        val file = java.io.File(directory, "last_book.json")
        val store = ResumeSnapshotStore(directory)
        assertNull(store.read())
        for (json in listOf("garbage", "{\"version\":1}",
            JSONObject(resumeSnapshot().toJson()).put("version", 2).toString(),
            resumeSnapshot().toJson().dropLast(5),
            JSONObject(resumeSnapshot().toJson()).put("version", 1.5).toString(),
            JSONObject(resumeSnapshot().toJson()).put("folderUri", JSONObject.NULL).toString(),
            JSONObject(resumeSnapshot().toJson()).put("tracks", org.json.JSONArray()).toString())) {
            assertNull(ResumeSnapshot.fromJson(json))
            file.writeText(json)
            assertNull(store.read())
            assertFalse(file.exists())
        }
    }

    @Test fun atomicWriteReplacesSnapshotAndLeavesNoTempFile() {
        val directory = temp.newFolder()
        val store = ResumeSnapshotStore(directory)
        store.write(resumeSnapshot())
        val updated = resumeSnapshot().copy(title = "Updated")
        store.write(updated)
        assertEquals(updated, store.read())
        assertEquals(listOf("last_book.json"), directory.listFiles()!!.map { it.name })
        store.clear()
        assertNull(store.read())
    }

    @Test fun readOnlyInspectionDoesNotDeleteCorruptSnapshot() {
        val directory = temp.newFolder()
        val file = java.io.File(directory, "last_book.json").apply { writeText("partial") }
        assertNull(ResumeSnapshotStore(directory).read(deleteInvalid = false))
        assertTrue(file.exists())
    }
}
