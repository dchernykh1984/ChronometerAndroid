package com.dchernykh.chronometer.ui

import androidx.work.WorkInfo

/** Coarse upload state shown in the on-screen diagnostics (never blocks cutoffs). */
enum class UploadStatus { IDLE, PENDING, RUNNING, SENT, FAILED }

/** Collapses the WorkManager states of the upload work into one status. */
fun uploadStatusOf(states: List<WorkInfo.State>): UploadStatus =
    when {
        states.any { it == WorkInfo.State.RUNNING } -> UploadStatus.RUNNING
        states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> UploadStatus.PENDING
        states.any { it == WorkInfo.State.FAILED } -> UploadStatus.FAILED
        states.any { it == WorkInfo.State.SUCCEEDED } -> UploadStatus.SENT
        else -> UploadStatus.IDLE
    }
