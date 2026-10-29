package com.dchernykh.chronometer.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.startprotocol.StartProtocolFile
import com.dchernykh.chronometer.util.TimeFormatter
import com.dchernykh.chronometer.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Orchestrates the durable-first recording sequence for each cutoff. */
class CutoffRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val backupWriter: BackupWriter,
    private val dao: CutoffDao = db.cutoffDao(),
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
        /** The tap's moment, taken before anything that may wait for [mutationMutex]. */
        now: Long = System.currentTimeMillis(),
    ) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val timeStr = TimeFormatter.currentTimeString(now)

        mutationMutex.withLock {
            val id =
                dao.insert(
                    CutoffEntity(
                        number = trimmed,
                        timeStr = timeStr,
                        event = event,
                        createdAt = now,
                    ),
                )
            afterChange(id)
        }
    }

    /**
     * What every change to the list does once it is in Room: the next revision, a
     * snapshot, the upload. Callers hold [mutationMutex]; a batch of source cutoffs calls
     * it once, so a mass finish writes one snapshot rather than one a cutoff.
     *
     * The snapshot is named by [stamp], a row id rather than the millisecond clock, so two
     * presses in the same millisecond never collide.
     */
    internal suspend fun afterChange(stamp: Long) {
        settingsStore.nextClientRevision()
        val settings = settingsStore.load()
        val items = dao.getAll().map { it.toItem() }
        val backupOk =
            withContext(Dispatchers.IO) {
                backupWriter.writeSnapshot(settings.folderPath, items, stamp)
            }
        backupFailedState.value = !backupOk
        if (settings.isUploadReady) {
            enqueueUpload()
        }
    }

    /**
     * Correct a mis-recorded cutoff: overwrite its number and time, then run the
     * same durable-file + upload sequence a fresh cutoff does. Only the number and
     * time change; the event, id and original createdAt stay. Returns false (row
     * left untouched) when the number is blank or the time is not a valid
     * "D H:M:S.mmm" string. `#` and line breaks are stripped from the number so an
     * edit can never break the `#`-delimited record.
     *
     * The snapshot is written under the row id, so it lands next to the original as
     * `backup/<id>-N.txt` (BackupWriter never overwrites a snapshot) and nothing in
     * the audit trail is lost.
     */
    suspend fun editCutoff(
        id: Long,
        number: String,
        timeStr: String,
    ): Boolean {
        val cleanedNumber =
            number
                .replace('#', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
        val cleanedTime = timeStr.trim()
        if (cleanedNumber.isEmpty() || !TimeFormatter.isValidTimeString(cleanedTime)) {
            return false
        }

        return mutationMutex.withLock {
            dao.updateNumberAndTime(id, cleanedNumber, cleanedTime)
            afterChange(id)
            true
        }
    }

    /** Refuse deletion until the complete original list has an immutable backup. */
    suspend fun deleteCutoff(id: Long): Boolean =
        mutationMutex.withLock {
            val before = dao.getAll()
            if (before.none { it.id == id }) {
                return@withLock false
            }
            val settings = settingsStore.load()
            val backedUp =
                withContext(Dispatchers.IO) {
                    backupWriter.writeSnapshot(settings.folderPath, before.map { it.toItem() }, id)
                }
            if (!backedUp) {
                backupFailedState.value = true
                return@withLock false
            }

            // Once deleted, always finish the new snapshot and queue the upload.
            withContext(NonCancellable) {
                // Persist intent before the delete so a restart can still send an empty list.
                settingsStore.allowEmptySnapshot()
                dao.deleteById(id)
                settingsStore.nextClientRevision()
                val after = dao.getAll().map { it.toItem() }
                val backupOk =
                    withContext(Dispatchers.IO) {
                        backupWriter.writeSnapshot(settings.folderPath, after, id)
                    }
                backupFailedState.value = !backupOk
                if (settings.isUploadReady) {
                    enqueueUpload()
                }
                true
            }
        }

    /**
     * Enqueue an upload for cutoffs already stored in Room. Called after the user
     * turns on or fixes the upload settings, so data recorded while sending was
     * off/misconfigured is pushed without waiting for the next new cutoff.
     */
    suspend fun uploadPendingCutoffs() =
        mutationMutex.withLock {
            if (settingsStore.load().isUploadReady &&
                (dao.getAll().isNotEmpty() || settingsStore.allowsEmptySnapshot())
            ) {
                enqueueUpload()
            }
        }

    /**
     * Start a new competition (spec FR-016): `start.txt` and the raw device files move into
     * `backup/`, `results.txt` is emptied, the cutoffs and what the sources left -- pending
     * beam breaks, chip reads, passes -- are cleared in one transaction, the token dropped and
     * the point number reset; the revision counter keeps counting. Backups on disk are
     * preserved. [onCleared] runs under the same lock, so no source event is applied with the
     * last competition's chip map.
     *
     * The files go first. If one cannot be moved or reset, the existing Room/settings state
     * is kept intact so the referee does not lose the current competition.
     */
    suspend fun startNewCompetition(onCleared: () -> Unit = {}): Boolean =
        mutationMutex.withLock {
            val folder = settingsStore.load().folderPath
            val filesOk =
                withContext(Dispatchers.IO) {
                    backupWriter.moveAside(folder, SET_ASIDE, System.currentTimeMillis()) &&
                        backupWriter.resetResults(folder)
                }
            if (!filesOk) {
                backupFailedState.value = true
                return@withLock false
            }
            db.withTransaction {
                dao.deleteAll()
                db.beamDao().clearBreaks()
                db.chipDao().clearReads()
                db.passDao().clearPasses()
            }
            settingsStore.resetForNewCompetition()
            onCleared()
            backupFailedState.value = false
            true
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

    companion object {
        // Writers and the worker must agree on the list, revision and snapshot filenames.
        internal val mutationMutex = Mutex()

        private const val UPLOAD_WORK = "chronometer_upload"

        /** One competition's files, moved into backup/ by a new competition. */
        private val SET_ASIDE = listOf(StartProtocolFile.NAME, RawFiles.CHIP_READS, RawFiles.BEAM_BREAKS)
    }
}
