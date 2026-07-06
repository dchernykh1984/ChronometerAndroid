package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo

/** Coarse upload state shown in the on-screen diagnostics (never blocks cutoffs). */
enum class UploadStatus { IDLE, PENDING, RUNNING, SENT, FAILED }

/**
 * Collapses the WorkManager states of the upload work into one status.
 *
 * WorkManager can return several [WorkInfo] for one unique work (terminal history
 * plus a current attempt). An active attempt wins first; among terminal results a
 * SUCCEEDED wins over a FAILED, so an older failed upload does not mask a later
 * successful one (each upload sends the whole snapshot, so any success means the
 * data got through).
 */
fun uploadStatusOf(states: List<WorkInfo.State>): UploadStatus =
    when {
        states.any { it == WorkInfo.State.RUNNING } -> UploadStatus.RUNNING
        states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> UploadStatus.PENDING
        states.any { it == WorkInfo.State.SUCCEEDED } -> UploadStatus.SENT
        states.any { it == WorkInfo.State.FAILED } -> UploadStatus.FAILED
        else -> UploadStatus.IDLE
    }
