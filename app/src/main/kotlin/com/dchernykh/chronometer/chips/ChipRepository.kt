package com.dchernykh.chronometer.chips

import androidx.room.withTransaction
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.ChipReadEntity
import com.dchernykh.chronometer.data.ChipSeen
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.PassEntity
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.hasAllowedUploadScheme
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.startprotocol.Download
import com.dchernykh.chronometer.startprotocol.DownloadProblem
import com.dchernykh.chronometer.startprotocol.StartListClient
import com.dchernykh.chronometer.startprotocol.StartListException
import com.dchernykh.chronometer.startprotocol.StartProtocolFile
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

private const val MS_PER_SECOND = 1_000L

// SQLite's oldest limit on the values of one statement is 999.
private const val CHIPS_A_QUERY = 900

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
 * The map changes and reads are applied under [CutoffRepository.mutationMutex], so a read
 * is never bound by a map older than the one whose change already took the waiting reads.
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

    /** Chips read that the map neither binds nor names as a conflict (FR-011), by id. */
    val unknown: Flow<List<ChipSeen>> =
        combine(db.chipDao().observeWaiting(), protocolState) { waiting, protocol ->
            val map = protocol?.map ?: ChipMap()
            waiting.filter { it.chip !in map.bibOf && it.chip !in map.conflicts }
        }

    /** Read `start.txt` if it has not been read yet: at start-up, never from the site. */
    suspend fun ensureMap() {
        if (protocolState.value == null) {
            reloadMap()
        }
    }

    /**
     * Read `start.txt` from the data folder again and use its map: the waiting reads of the
     * chips it binds join their bibs' passes, and a pass that starts makes its cutoff, in time
     * order (FR-011). A chip that moved to another bib keeps its past cutoffs on the old one.
     */
    suspend fun reloadMap() {
        // Read under the lock too, so reloads take effect in the order they read the file.
        CutoffRepository.mutationMutex.withLock {
            val folder = settingsStore.load().folderPath
            val protocol =
                withContext(Dispatchers.IO) {
                    try {
                        StartProtocolFile.read(folder)?.let { StartProtocol.Loaded(ChipMap.of(it)) }
                            ?: StartProtocol.Missing
                    } catch (e: IOException) {
                        StartProtocol.Unreadable(e.message ?: e.javaClass.simpleName)
                    }
                }
            protocolState.value = protocol
            val settings = settingsStore.load()
            val lastCutoff = db.withTransaction { timeWaitingReads(protocol.map, settings) }
            lastCutoff?.let { cutoffs.afterChange(it) }
        }
    }

    /**
     * "Download start protocol" (FR-009): the site's start list replaces start.txt, the old
     * one kept in `backup/`, and its map is used at once. Asked only from the button: nothing
     * else in the app contacts the site for it (SC-005).
     */
    suspend fun download(client: StartListClient = StartListClient()): Download {
        val settings = settingsStore.load()
        if (!hasAllowedUploadScheme(settings.siteUrl) || settings.token.isBlank()) {
            return Download.Failed(DownloadProblem.NO_SITE)
        }
        return try {
            val lines = withContext(Dispatchers.IO) { client.fetch(settings.siteUrl, settings.token) }
            val written =
                withContext(
                    Dispatchers.IO,
                ) { StartProtocolFile.replace(settings.folderPath, lines, now(), backupWriter) }
            if (written) {
                reloadMap()
                Download.Done(lines.size, protocolState.value?.map ?: ChipMap())
            } else {
                Download.Failed(DownloadProblem.FILE)
            }
        } catch (e: StartListException) {
            Download.Failed(e.problem, e.detail)
        }
    }

    /** The waiting reads of chips [map] binds, into passes; the id of the last cutoff made. */
    private suspend fun timeWaitingReads(
        map: ChipMap,
        settings: Settings,
    ): Long? {
        val dao = db.chipDao()
        val bound = dao.waitingChips().filter { it in map.bibOf }
        val reads = bound.chunked(CHIPS_A_QUERY).flatMap { dao.waitingReadsOf(it) }
        val deadMs = settings.deadTimeS * MS_PER_SECOND
        val made = mutableListOf<ChipReadEntity>()
        for ((bib, ofBib) in reads.groupBy { map.bibOf.getValue(it.chip) }) {
            // Grouped first, as ChronometerPython's set_map: a group near a pass makes nothing,
            // however much earlier its first read is.
            for (group in groups(ofBib.sortedWith(compareBy({ it.timeMs }, { it.id })), deadMs)) {
                if (intoPasses(bib, group.first().timeMs, group.last().timeMs, deadMs)) {
                    made += group.first().copy(bib = bib)
                }
            }
        }
        bound.forEach { dao.attribute(it, map.bibOf.getValue(it)) }
        return made
            .sortedBy { it.timeMs }
            .map { db.cutoffDao().insert(cutoffOf(checkNotNull(it.bib), it.timeStr, settings.finishMode)) }
            .lastOrNull()
    }

    /** Reads sorted by time, joined wherever less than the dead time apart. */
    private fun groups(
        reads: List<ChipReadEntity>,
        deadMs: Long,
    ): List<List<ChipReadEntity>> {
        val groups = mutableListOf<MutableList<ChipReadEntity>>()
        for (read in reads) {
            val last = groups.lastOrNull()
            if (last != null && read.timeMs < last.last().timeMs + deadMs) {
                last += read
            } else {
                groups += mutableListOf(read)
            }
        }
        return groups
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
        val rows =
            CutoffRepository.mutationMutex.withLock {
                val map = protocolState.value?.map ?: ChipMap()
                val rows = reads.map { read -> rowOf(read, map) }
                db.withTransaction { storeAndTime(rows, settings) }?.let { cutoffs.afterChange(it) }
                rows
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

    /** [rows] stored, and each read of a bound chip into its bib's passes; the last cutoff's id. */
    private suspend fun storeAndTime(
        rows: List<ChipReadEntity>,
        settings: Settings,
    ): Long? {
        db.chipDao().insertReads(rows)
        val deadMs = settings.deadTimeS * MS_PER_SECOND
        var last: Long? = null
        for (row in rows) {
            val bib = row.bib ?: continue
            if (intoPasses(bib, row.timeMs, row.timeMs, deadMs)) {
                last = db.cutoffDao().insert(cutoffOf(bib, row.timeStr, settings.finishMode))
            }
        }
        return last
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
     * The pass rule (spec FR-010, ChronometerPython's `_into_passes`): reads from [first] to
     * [last] less than the dead time from passes of their bib join them, merging them into
     * one, and make nothing; otherwise they are a new pass. True for a new pass. A cutoff is
     * never moved, and a pass whose cutoff the referee deleted or edited still holds its reads.
     */
    private suspend fun intoPasses(
        bib: String,
        first: Long,
        last: Long,
        deadMs: Long,
    ): Boolean {
        val dao = db.passDao()
        val near = dao.passesOf(bib).filter { it.firstMs - deadMs < last && first < it.lastMs + deadMs }
        if (near.isEmpty()) {
            dao.insertPass(PassEntity(bib = bib, firstMs = first, lastMs = last))
            return true
        }
        val merged =
            near.first().copy(
                firstMs = minOf(first, near.minOf { it.firstMs }),
                lastMs = maxOf(last, near.maxOf { it.lastMs }),
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
