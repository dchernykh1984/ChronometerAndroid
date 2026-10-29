package com.dchernykh.chronometer.beam

import androidx.room.withTransaction
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.PendingBreakEntity
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Beam breaks waiting for the referee to give each a number (spec US4, FR-013): kept in
 * Room first, then in the raw beam file; the oldest takes the next typed number.
 */
class BeamRepository(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val backupWriter: BackupWriter,
    private val cutoffs: CutoffRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.beamDao()
    private val rawFailedState = MutableStateFlow(false)

    init {
        cutoffs.whenANewCompetitionStarts(::startOver)
    }

    /** Oldest first. */
    val pending: Flow<List<PendingBreakEntity>> = dao.observeBreaks()

    /** True once the raw beam file missed lines, until a new competition; the breaks are in Room. */
    val rawFailed: StateFlow<Boolean> = rawFailedState.asStateFlow()

    /** Breaks taken in the competition [takenIn]: dropped once a new one has started. */
    suspend fun add(
        breaks: List<SourceEvent.BeamBreak>,
        takenIn: Long = cutoffs.competition,
    ) {
        if (breaks.isEmpty()) {
            return
        }
        val rows =
            breaks.map {
                PendingBreakEntity(
                    seq = it.seq,
                    timeStr = TimeFormatter.currentTimeString(it.timeMs ?: now()),
                    deviceTime = RawFiles.field(it.deviceTime),
                )
            }
        CutoffRepository.mutationMutex.withLock {
            if (takenIn != cutoffs.competition) {
                return@withLock // the last competition's: its tables are gone
            }
            db.withTransaction { rows.forEach { dao.insertBreak(it) } }
            val lines = rows.map { "${it.seq}#${it.timeStr}#${it.deviceTime}#" }
            // Under the lock too: a new competition moves the file aside before or after it.
            val written =
                withContext(Dispatchers.IO) {
                    backupWriter.appendRaw(settingsStore.load().folderPath, RawFiles.BEAM_BREAKS, lines)
                }
            // The file only grows: lines it missed stay missing, and so does the warning.
            rawFailedState.value = rawFailedState.value || !written
        }
    }

    /**
     * Give [number] to the break [id] the Cutoff button showed: a cutoff at the break's time,
     * made on the durable path. False when that break waits no more, or the number is blank.
     * A break stored while the tap waited for the lock is not taken: the button did not show it.
     */
    suspend fun assign(
        id: Long,
        number: String,
        event: String,
    ): Boolean {
        // No `#` or line break may reach the `number#time#event#` line.
        val trimmed = RawFiles.field(number)
        if (trimmed.isEmpty()) {
            return false
        }
        return CutoffRepository.mutationMutex.withLock {
            // Read and delete in one transaction: a Discard at the same moment cannot also
            // become a cutoff.
            val cutoff =
                db.withTransaction {
                    dao.breakById(id)?.let { shown ->
                        dao.deleteBreak(shown.id)
                        db.cutoffDao().insert(
                            CutoffEntity(number = trimmed, timeStr = shown.timeStr, event = event, createdAt = now()),
                        )
                    }
                } ?: return@withLock false
            cutoffs.afterChange(cutoff)
            true
        }
    }

    /** A new competition: the raw beam file starts afresh, so its last failure is no more. */
    private fun startOver() {
        rawFailedState.value = false
    }

    /** A false break leaves the queue; the raw file keeps it. */
    suspend fun discard(id: Long) {
        dao.deleteBreak(id)
    }
}
