package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo
import com.dchernykh.chronometer.data.UploadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class UploadDiagnosticsTest {
    private val id = UUID.randomUUID()
    private val error = UploadError(id.toString(), "HTTP 403: Invalid competition token")

    @Test
    fun failedAndRetryingWorkShowTheirOwnReason() {
        for (state in listOf(WorkInfo.State.FAILED, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
            val diagnostics = uploadDiagnosticsOf(listOf(WorkInfo(id, state, emptySet())), error)
            assertEquals(error.message, diagnostics.error)
            assertEquals(
                if (state ==
                    WorkInfo.State.FAILED
                ) {
                    UploadStatus.FAILED
                } else {
                    UploadStatus.PENDING
                },
                diagnostics.status,
            )
        }
    }

    @Test
    fun anotherRequestNeverShowsTheOldError() {
        val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet())
        assertNull(uploadDiagnosticsOf(listOf(work), error).error)
    }

    @Test
    fun runningSucceededCancelledAndMissingWorkHideTheOldError() {
        for (state in listOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)) {
            assertNull(uploadDiagnosticsOf(listOf(WorkInfo(id, state, emptySet())), error).error)
        }
        assertEquals(UploadDiagnostics(), uploadDiagnosticsOf(emptyList(), error))
    }

    @Test
    fun legacyFailuresWithoutADiagnosticStillShowFailedStatus() {
        val work = WorkInfo(id, WorkInfo.State.FAILED, emptySet())
        assertEquals(UploadDiagnostics(UploadStatus.FAILED), uploadDiagnosticsOf(listOf(work), null))
    }
}
