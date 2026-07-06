package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadStatusTest {
    @Test
    fun noWorkIsIdle() {
        assertEquals(UploadStatus.IDLE, uploadStatusOf(emptyList()))
    }

    @Test
    fun runningTakesPriority() {
        assertEquals(
            UploadStatus.RUNNING,
            uploadStatusOf(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)),
        )
    }

    @Test
    fun enqueuedIsPending() {
        assertEquals(UploadStatus.PENDING, uploadStatusOf(listOf(WorkInfo.State.ENQUEUED)))
    }

    @Test
    fun succeededIsSent() {
        assertEquals(UploadStatus.SENT, uploadStatusOf(listOf(WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun failedIsFailed() {
        assertEquals(UploadStatus.FAILED, uploadStatusOf(listOf(WorkInfo.State.FAILED)))
    }

    @Test
    fun succeededIsNotMaskedByAnOlderFailedWork() {
        assertEquals(
            UploadStatus.SENT,
            uploadStatusOf(listOf(WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED)),
        )
        assertEquals(
            UploadStatus.SENT,
            uploadStatusOf(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)),
        )
    }

    @Test
    fun queuedRetryTakesPriorityOverAnOlderFailure() {
        assertEquals(
            UploadStatus.PENDING,
            uploadStatusOf(listOf(WorkInfo.State.FAILED, WorkInfo.State.ENQUEUED)),
        )
    }
}
