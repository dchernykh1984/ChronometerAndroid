package com.dchernykh.chronometer.chips

import androidx.room.withTransaction
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.ChipReadEntity
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.PassEntity
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.startprotocol.StartProtocolFile
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

private const val MS_PER_SECOND = 1_000L

/** What `start.txt` gave the chip map; [map] is empty without a usable file. */
sealed interface StartProtocol {
    val map: ChipMap get() = ChipMap()

    data object Missing : StartProtocol

    data class Unreadable(
        val reason: String,
    ) : StartProtocol

    data class Loaded(
        override val map: ChipMap,
    ) : StartProtocol
}

/**
 * The chips side of timing (spec US2, US3): the map from `start.txt`, and chip reads turned
 * into cutoffs by the pass rule, in one transaction with the reads (research decision 3).
 */
class ChipRepository(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val backupWriter: BackupWriter,
    private val cutoffs: CutoffRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val protocolState = MutableStateFlow<StartProtocol?>(null)
    private val rawFailedState = MutableStateFlow(false)

    /** `start.txt` as last read; null before the first read. */
    val protocol: StateFlow<StartProtocol?> = protocolState.asStateFlow()

    /** True after the raw reads file could not be written; the reads are in Room. */
    val rawFailed: StateFlow<Boolean> = rawFailedState.asStateFlow()

    /** Read `start.txt` if it has not been read yet: at start-up, never from the site. */
    suspend fun ensureMap() {
        if (protocolState.value == null) {
            reloadMap()
        }
    }

    /** Read `start.txt` from the data folder again and rebuild the map. */
    suspend fun reloadMap() {
        val folder = settingsStore.load().folderPath
        protocolState.value =
            withContext(Dispatchers.IO) {
                try {
                    StartProtocolFile.read(folder)?.let { StartProtocol.Loaded(ChipMap.of(it)) }
                        ?: StartProtocol.Missing
                } catch (e: IOException) {
                    StartProtocol.Unreadable(e.message ?: e.javaClass.simpleName)
                }
            }
    }

    /**
     * Store [reads] in arrival order and give each read of a mapped chip to its bib's passes;
     * a read that starts a pass makes its cutoff (spec FR-007, FR-010, FR-012). Reads, passes
     * and cutoffs commit together, then the cutoffs take the durable path once for the batch
     * and the raw reads file gets the lines.
     */
    suspend fun apply(reads: List<SourceEvent.ChipRead>) {
        if (reads.isEmpty()) {
            return
        }
        ensureMap()
        val settings = settingsStore.load()
        val map = protocolState.value?.map ?: ChipMap()
        val rows = reads.map { read -> rowOf(read, map) }
        CutoffRepository.mutationMutex.withLock {
            val lastCutoff =
                db.withTransaction {
                    db.chipDao().insertReads(rows)
                    var last: Long? = null
                    for (row in rows) {
                        val bib = row.bib ?: continue
                        if (intoPasses(bib, row.timeMs, settings.deadTimeS * MS_PER_SECOND)) {
                            last = db.cutoffDao().insert(cutoffOf(bib, row.timeStr, settings.finishMode))
                        }
                    }
                    last
                }
            lastCutoff?.let { cutoffs.afterChange(it) }
        }
        val lines =
            rows.map {
                "${it.chip}#${it.timeStr}#${it.deviceTime}#${it.antenna}#${RawFiles.signal(it.rssi)}#${it.reader}#"
            }
        val written =
            withContext(Dispatchers.IO) {
                backupWriter.appendRaw(settings.folderPath, RawFiles.CHIP_READS, lines)
            }
        rawFailedState.value = !written
    }

    private fun rowOf(
        read: SourceEvent.ChipRead,
        map: ChipMap,
    ): ChipReadEntity {
        val chip = normaliseChip(read.chip)
        val timeStr = TimeFormatter.currentTimeString(read.timeMs ?: now())
        return ChipReadEntity(
            chip = chip,
            timeStr = timeStr,
            timeMs = checkNotNull(TimeFormatter.millisOf(timeStr)),
            deviceTime = RawFiles.field(read.deviceTime),
            antenna = RawFiles.field(read.antenna),
            reader = RawFiles.field(read.reader),
            rssi = read.rssi,
            bib = map.bibOf[chip],
        )
    }

    private fun cutoffOf(
        bib: String,
        timeStr: String,
        finishMode: Boolean,
    ) = CutoffEntity(number = bib, timeStr = timeStr, event = CutoffEvent.lapEvent(finishMode), createdAt = now())

    /**
     * The pass rule (spec FR-010, ChronometerPython's `_into_passes`): a read less than the
     * dead time from passes of its bib joins them, merging them into one, and makes nothing;
     * otherwise it is a new pass. True for a new pass. A cutoff is never moved, and a pass
     * whose cutoff the referee deleted or edited still holds its reads.
     */
    private suspend fun intoPasses(
        bib: String,
        at: Long,
        deadMs: Long,
    ): Boolean {
        val dao = db.passDao()
        val near = dao.passesOf(bib).filter { it.firstMs - deadMs < at && at < it.lastMs + deadMs }
        if (near.isEmpty()) {
            dao.insertPass(PassEntity(bib = bib, firstMs = at, lastMs = at))
            return true
        }
        val merged =
            near.first().copy(
                firstMs = minOf(at, near.minOf { it.firstMs }),
                lastMs = maxOf(at, near.maxOf { it.lastMs }),
            )
        if (merged != near.first()) {
            dao.updatePass(merged)
        }
        if (near.size > 1) {
            dao.deletePasses(near.drop(1).map { it.id })
        }
        return false
    }
}
