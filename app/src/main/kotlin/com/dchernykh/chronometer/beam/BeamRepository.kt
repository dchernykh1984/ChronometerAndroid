package com.dchernykh.chronometer.beam

import androidx.room.withTransaction
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.PendingBreakEntity
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.io.RawLog
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

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
    private val raw = RawLog(RawFiles.BEAM_BREAKS, backupWriter)

    init {
        cutoffs.whenANewCompetitionStarts(::startOver)
    }

    /** Oldest first. */
    val pending: Flow<List<PendingBreakEntity>> = dao.observeBreaks()

    /** True once the raw beam file missed lines, until a new competition; the breaks are in Room. */
    val rawFailed: StateFlow<Boolean> = raw.failed

    /** Breaks taken in the competition [takenIn]: dropped once a new one has started. */
    suspend fun add(
        breaks: List<SourceEvent.BeamBreak>,
        takenIn: Long = cutoffs.competition,
        /** False on a retry: the raw lines were written on the first try. */
        rawToo: Boolean = true,
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
            // The raw file first, under the lock a new competition moves it aside under: what
            // the device reported is kept even when Room cannot take it.
            if (rawToo) {
                raw.append(settingsStore.load().folderPath, rows.map { "${it.seq}#${it.timeStr}#${it.deviceTime}#" })
            }
            db.withTransaction { rows.forEach { dao.insertBreak(it) } }
        }
    }

    /**
     * Give [number] to the break [id] the Cutoff button showed -- or, when an earlier tap took
     * it as this one waited, the next waiting after it, as two quick taps mean two riders -- a
     * cutoff at the break's time, made on the durable path. False when none waits from it on,
     * or the number is blank. A tap that saw no break never comes here: a break stored while
     * it waited for the lock is the next rider's.
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
                    dao.oldestBreakFrom(id)?.let { shown ->
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
        raw.startOver()
    }

    /** A false break leaves the queue; the raw file keeps it. */
    suspend fun discard(id: Long) {
        dao.deleteBreak(id)
    }
}
