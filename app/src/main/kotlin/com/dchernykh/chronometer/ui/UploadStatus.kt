package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo
import com.dchernykh.chronometer.data.UploadError
import com.dchernykh.chronometer.work.UploadWorker

/** Coarse upload state shown in the on-screen diagnostics (never blocks cutoffs). */
enum class UploadStatus {
    IDLE,
    PENDING,
    RUNNING,
    SENT,
    FAILED,
    ;

    /** An upload not through yet: queued, waiting to retry or running. */
    val notThrough: Boolean get() = this == PENDING || this == RUNNING
}

data class UploadDiagnostics(
    val status: UploadStatus = UploadStatus.IDLE,
    val error: String? = null,
)

/** Only show an error for the work currently failed or waiting for a retry. */
fun uploadDiagnosticsOf(
    infos: List<WorkInfo>,
    error: UploadError?,
): UploadDiagnostics {
    val status = uploadStatusOf(infos)
    val relevantError =
        infos.any {
            it.id.toString() == error?.workId &&
                when (status) {
                    UploadStatus.FAILED -> it.state == WorkInfo.State.FAILED
                    UploadStatus.PENDING -> it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    else -> false
                }
        }
    return UploadDiagnostics(status, error?.message?.takeIf { relevantError })
}

/**
 * Collapses the WorkManager states of the upload work into one status.
 *
 * WorkManager can return several [WorkInfo] for one unique work (terminal history
 * plus a current attempt). An active attempt wins first; among terminal results a
 * snapshot sent wins over a FAILED, so an older failed upload does not mask a later
 * successful one (each upload sends the whole snapshot, so any snapshot sent means the
 * data got through). A success that sent nothing ([UploadWorker.SENT] not set) is no
 * upload, as a cancelled one: the idle line, never "sent".
 */
fun uploadStatusOf(infos: List<WorkInfo>): UploadStatus =
    when {
        infos.any { it.state == WorkInfo.State.RUNNING } -> UploadStatus.RUNNING
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> UploadStatus.PENDING
        infos.any { it.state == WorkInfo.State.SUCCEEDED && it.outputData.getBoolean(UploadWorker.SENT, false) } ->
            UploadStatus.SENT
        infos.any { it.state == WorkInfo.State.FAILED } -> UploadStatus.FAILED
        else -> UploadStatus.IDLE
    }
