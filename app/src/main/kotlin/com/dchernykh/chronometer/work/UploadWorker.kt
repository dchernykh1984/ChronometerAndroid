package com.dchernykh.chronometer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.isUploadReady
import com.dchernykh.chronometer.net.UploadResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Uploads the current snapshot of cutoffs. Enqueued with a CONNECTED constraint
 * so it runs off the UI thread and is retried automatically when the network
 * returns (even after the app was closed). Transient failures return retry;
 * permanent (configuration) failures return failure so WorkManager stops
 * retrying them.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChronometerApp
        val settings = app.settingsStore.load()
        if (!settings.isUploadReady) {
            return Result.success()
        }
        val items =
            app.database
                .cutoffDao()
                .getAll()
                .map { it.toItem() }
        if (items.isEmpty()) {
            return Result.success()
        }
        val outcome =
            app.uploadClient.upload(
                siteUrl = settings.siteUrl,
                token = settings.token,
                deviceId = settings.deviceUuid,
                pointNumber = settings.pointNumber,
                items = items,
                clientRevision = app.settingsStore.clientRevision(),
            )
        // A replaced/cancelled request must not overwrite the current diagnostic.
        currentCoroutineContext().ensureActive()
        outcome.error?.let { app.uploadErrorStore.record(id.toString(), it) }
        return when (outcome.result) {
            UploadResult.SUCCESS -> Result.success()
            UploadResult.RETRY -> Result.retry()
            UploadResult.GIVE_UP -> Result.failure()
        }
    }
}
