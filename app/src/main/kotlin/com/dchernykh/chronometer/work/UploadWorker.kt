package com.dchernykh.chronometer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.isUploadReady
import com.dchernykh.chronometer.net.UploadResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Uploads the current snapshot of cutoffs. Enqueued with a CONNECTED constraint
 * so it runs off the UI thread and is retried automatically when the network
 * returns (even after the app was closed). Transient failures return retry;
 * permanent (configuration) failures return failure so WorkManager stops
 * retrying them. Only a snapshot the site took is a success saying [SENT]; nothing
 * to send -- a fresh empty list, or settings that no longer send where the work was
 * not cancelled -- is a success without it, shown as no upload.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChronometerApp
        val (settings, items, revision) =
            CutoffRepository.mutationMutex.withLock {
                val settings = app.settingsStore.load()
                val items =
                    app.database
                        .cutoffDao()
                        .getAll()
                        .map { it.toItem() }
                if (!settings.isUploadReady || (items.isEmpty() && !app.settingsStore.allowsEmptySnapshot())) {
                    return Result.success()
                }
                Triple(settings, items, app.settingsStore.clientRevision())
            }
        val outcome =
            app.uploadClient.upload(
                siteUrl = settings.siteUrl,
                token = settings.token,
                deviceId = settings.deviceUuid,
                pointNumber = settings.pointNumber,
                items = items,
                clientRevision = revision,
            )
        // A replaced/cancelled request must not overwrite the current diagnostic.
        currentCoroutineContext().ensureActive()
        outcome.error?.let { app.uploadErrorStore.record(id.toString(), it) }
        return when (outcome.result) {
            UploadResult.SUCCESS -> Result.success(workDataOf(SENT to true))
            UploadResult.RETRY -> Result.retry()
            UploadResult.GIVE_UP -> Result.failure()
        }
    }

    companion object {
        /** True in a success's output when the site took the snapshot. */
        const val SENT = "sent"
    }
}
