package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.dchernykh.chronometer.work.UploadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UploadStatusTest {
    private fun work(
        state: WorkInfo.State,
        sent: Boolean = false,
    ) = WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = workDataOf(UploadWorker.SENT to sent))

    private fun statusOf(vararg works: WorkInfo) = uploadStatusOf(works.toList())

    @Test
    fun noWorkIsIdle() {
        assertEquals(UploadStatus.IDLE, uploadStatusOf(emptyList()))
    }

    @Test
    fun runningTakesPriority() {
        assertEquals(
            UploadStatus.RUNNING,
            statusOf(work(WorkInfo.State.ENQUEUED), work(WorkInfo.State.RUNNING)),
        )
    }

    @Test
    fun enqueuedIsPending() {
        assertEquals(UploadStatus.PENDING, statusOf(work(WorkInfo.State.ENQUEUED)))
    }

    @Test
    fun aSnapshotSentIsSent() {
        assertEquals(UploadStatus.SENT, statusOf(work(WorkInfo.State.SUCCEEDED, sent = true)))
    }

    @Test
    fun aSuccessThatSentNothingIsNoUpload() {
        assertEquals(UploadStatus.IDLE, statusOf(work(WorkInfo.State.SUCCEEDED)))
        assertEquals(
            UploadStatus.IDLE,
            statusOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet())),
        )
    }

    @Test
    fun aCancelledUploadIsNoUpload() {
        assertEquals(UploadStatus.IDLE, statusOf(work(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun failedIsFailed() {
        assertEquals(UploadStatus.FAILED, statusOf(work(WorkInfo.State.FAILED)))
    }

    @Test
    fun succeededIsNotMaskedByAnOlderFailedWork() {
        assertEquals(
            UploadStatus.SENT,
            statusOf(work(WorkInfo.State.FAILED), work(WorkInfo.State.SUCCEEDED, sent = true)),
        )
        assertEquals(
            UploadStatus.SENT,
            statusOf(work(WorkInfo.State.SUCCEEDED, sent = true), work(WorkInfo.State.FAILED)),
        )
    }

    @Test
    fun queuedRetryTakesPriorityOverAnOlderFailure() {
        assertEquals(
            UploadStatus.PENDING,
            statusOf(work(WorkInfo.State.FAILED), work(WorkInfo.State.ENQUEUED)),
        )
    }

    @Test
    fun anUploadQueuedWaitingToRetryOrRunningIsNotThrough() {
        assertTrue(UploadStatus.PENDING.notThrough)
        assertTrue(UploadStatus.RUNNING.notThrough)
        for (status in listOf(UploadStatus.IDLE, UploadStatus.SENT, UploadStatus.FAILED)) {
            assertFalse(status.name, status.notThrough)
        }
    }
}
