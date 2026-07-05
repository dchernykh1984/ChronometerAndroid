package com.dchernykh.chronometer.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.util.TimeFormatter
import com.dchernykh.chronometer.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Orchestrates the durable-first recording sequence for each cutoff. */
class CutoffRepository(
    private val context: Context,
    private val dao: CutoffDao,
    private val settingsStore: SettingsStore,
    private val backupWriter: BackupWriter,
) {
    val cutoffs: Flow<List<CutoffEntity>> = dao.observeNewestFirst()

    /**
     * 1) Durable write to Room BEFORE anything else, 2) snapshot to the files,
     * 3) enqueue the async upload. Steps 2-3 never block or fail the cutoff.
     */
    suspend fun record(
        number: String,
        disqualified: Boolean,
    ) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val timeStr = TimeFormatter.currentTimeString(now)

        dao.insert(
            CutoffEntity(
                number = trimmed,
                timeStr = timeStr,
                disqualified = disqualified,
                createdAt = now,
            ),
        )
        settingsStore.nextClientRevision()

        val settings = settingsStore.load()
        val items = dao.getAll().map { it.toItem() }
        withContext(Dispatchers.IO) {
            backupWriter.writeSnapshot(settings.folderPath, items, now)
        }

        if (settings.sendEnabled && settings.siteUrl.isNotBlank()) {
            enqueueUpload()
        }
    }

    private fun enqueueUpload() {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val UPLOAD_WORK = "chronometer_upload"
    }
}
