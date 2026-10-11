package com.janreins.audiobook

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.janreins.audiobook.data.ResumeSnapshot
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.player.PlaybackResumption
import com.janreins.audiobook.player.CoverBitmapLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackResumptionTest {
    private var snapshot: ResumeSnapshot? = resumeSnapshot()
    private var saved = BookProgress(1, 4000)
    private var finished = false
    private var allowed = true
    private var exists = true
    private var writes = 0
    private var deleteInvalid: Boolean? = null
    private var queried: String? = null
    private fun resumption(executor: Executor = Executor { it.run() }) = PlaybackResumption(
        { deleteInvalid = it; snapshot }, { saved }, { finished }, { allowed },
        { queried = it; exists }, { writes++ }, executor)

    @Test fun failureBranchesAreUnsupportedAndFlaggedOnlyForPlayback() {
        for (branch in 0..2) for (play in listOf(false, true)) {
            snapshot = if (branch == 0) null else resumeSnapshot()
            allowed = branch != 1; exists = branch != 2; writes = 0
            try {
                resumption().resume(play).get()
                fail("Expected unavailable branch $branch")
            } catch (e: ExecutionException) { assertTrue(e.cause is UnsupportedOperationException) }
            assertEquals(if (play) 1 else 0, writes)
            assertEquals(play, deleteInvalid)
        }
    }

    @Test fun successHasIdenticalMediaIdsMetadataAndSavedStart() {
        val result = resumption().resume(true).get()
        assertEquals(listOf("book#0", "book#1"), result.mediaItems.map { it.mediaId })
        assertEquals(1, result.startIndex)
        assertEquals(4000L, result.startPositionMs)
        assertEquals("Two", result.mediaItems[1].mediaMetadata.title)
        assertEquals("Book", result.mediaItems[1].mediaMetadata.albumTitle)
        assertEquals("book", result.mediaItems[1].mediaMetadata.extras!!.getString(CoverBitmapLoader.EXTRA_BOOK_ID))
        assertEquals(snapshot!!.tracks[1].uri, result.mediaItems[1].localConfiguration!!.uri.toString())
        assertEquals(snapshot!!.tracks[1].uri, queried)
        assertEquals(0, writes)
    }

    @Test fun finishedAtEndRestartsAndChecksFirstTrack() {
        saved = BookProgress(1, 10000); finished = true
        val result = resumption().resume(true).get()
        assertEquals(0, result.startIndex)
        assertEquals(0L, result.startPositionMs)
        assertEquals(snapshot!!.tracks[0].uri, queried)
    }

    @Test fun targetAfterResumePointAdvancesIsTheTrackChecked() {
        saved = BookProgress(0, 9500)
        assertEquals(1, resumption().resume(true).get().startIndex)
        assertEquals(snapshot!!.tracks[1].uri, queried)
    }

    @Test fun readOnlySuccessWritesNothingAndLeavesProgressAndFinishedAlone() {
        saved = BookProgress(1, 10000); finished = true
        resumption().resume(false).get()
        assertEquals(0, writes)
        assertEquals(false, deleteInvalid)
        assertEquals(BookProgress(1, 10000), saved)
        assertTrue(finished)
    }

    @Test fun workRunsOnInjectedExecutorWithoutBlockingCaller() {
        var task: Runnable? = null
        val future = resumption(Executor { task = it }).resume(true)
        assertFalse(future.isDone)
        assertNull(deleteInvalid)
        task!!.run()
        assertTrue(future.isDone)
        assertEquals(1, future.get().startIndex)
    }
}
