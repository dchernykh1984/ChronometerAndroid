package com.dchernykh.chronometer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dchernykh.chronometer.ChronometerApp

/**
 * Uploads the current snapshot of cutoffs. Enqueued with a CONNECTED constraint
 * so it runs off the UI thread and is retried automatically when the network
 * returns (even after the app was closed). Returns retry on a failed send so
 * WorkManager backs off and tries again.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChronometerApp
        val settings = app.settingsStore.load()
        if (!settings.sendEnabled || settings.siteUrl.isBlank()) {
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
        val sent =
            app.uploadClient.upload(
                siteUrl = settings.siteUrl,
                token = settings.token,
                deviceId = settings.deviceUuid,
                pointNumber = settings.pointNumber,
                items = items,
                clientRevision = app.settingsStore.clientRevision(),
            )
        return if (sent) Result.success() else Result.retry()
    }
}
