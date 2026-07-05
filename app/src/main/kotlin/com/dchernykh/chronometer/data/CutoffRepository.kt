package com.dchernykh.chronometer.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.util.TimeFormatter
import com.dchernykh.chronometer.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Orchestrates the durable-first recording sequence for each cutoff. */
class CutoffRepository(
    private val context: Context,
    private val dao: CutoffDao,
    private val settingsStore: SettingsStore,
    private val backupWriter: BackupWriter,
) {
    val cutoffs: Flow<List<CutoffEntity>> = dao.observeNewestFirst()

    private val backupFailedState = MutableStateFlow(false)

    /** True after the most recent cutoff failed to write its local files. */
    val backupFailed: StateFlow<Boolean> = backupFailedState.asStateFlow()

    /** Live state of the background upload work (for the on-screen diagnostics). */
    val uploadWorkInfo: Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UPLOAD_WORK)

    /**
     * 1) Durable write to Room BEFORE anything else, 2) snapshot to the files,
     * 3) enqueue the async upload. Steps 2-3 never block or fail the cutoff; a
     * failed file write is recorded in [backupFailed] and surfaced on screen.
     */
    suspend fun record(
        number: String,
        event: String,
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
                event = event,
                createdAt = now,
            ),
        )
        settingsStore.nextClientRevision()

        val settings = settingsStore.load()
        val items = dao.getAll().map { it.toItem() }
        val backupOk =
            withContext(Dispatchers.IO) {
                backupWriter.writeSnapshot(settings.folderPath, items, now)
            }
        backupFailedState.value = !backupOk

        if (settings.isUploadReady) {
            enqueueUpload()
        }
    }

    /**
     * Start a new competition: empty `results.txt`, clear cutoffs, drop the token,
     * reset the point number and revision. Backups on disk are preserved.
     *
     * The file reset happens first. If it fails, the existing Room/settings state is
     * kept intact so the referee does not lose the current competition.
     */
    suspend fun startNewCompetition(): Boolean {
        val folder = settingsStore.load().folderPath
        val resetOk = withContext(Dispatchers.IO) { backupWriter.resetResults(folder) }
        if (!resetOk) {
            backupFailedState.value = true
            return false
        }
        dao.deleteAll()
        settingsStore.resetForNewCompetition()
        backupFailedState.value = false
        return true
    }

    /** Size of the data folder (results + backups) for the settings info line. */
    suspend fun dataFolderSizeBytes(): Long =
        withContext(Dispatchers.IO) {
            backupWriter.folderSizeBytes(settingsStore.load().folderPath)
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
