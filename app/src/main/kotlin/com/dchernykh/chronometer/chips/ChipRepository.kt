package com.dchernykh.chronometer.chips

import android.util.Log
import androidx.room.withTransaction
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.ChipReadEntity
import com.dchernykh.chronometer.data.ChipSeen
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.PassDao
import com.dchernykh.chronometer.data.PassEntity
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.hasAllowedUploadScheme
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.io.RawFiles
import com.dchernykh.chronometer.io.RawLog
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.startprotocol.Download
import com.dchernykh.chronometer.startprotocol.DownloadProblem
import com.dchernykh.chronometer.startprotocol.StartListClient
import com.dchernykh.chronometer.startprotocol.StartListException
import com.dchernykh.chronometer.startprotocol.StartProtocolFile
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ChipRepository"

// SQLite's oldest limit on the values of one statement is 999.
private const val CHIPS_A_QUERY = 900

/** What `start.txt` gave the chip map; [map] is empty without a usable file. */
sealed interface StartProtocol {
    val map: ChipMap

    /** There is none; [map] is the one read before it, kept in use mid-race. */
    data class Missing(
        override val map: ChipMap = ChipMap(),
    ) : StartProtocol

    /** It could not be read; [map] is the one read before it, kept in use mid-race. */
    data class Unreadable(
        val reason: String,
        override val map: ChipMap = ChipMap(),
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
    private val raw = RawLog(RawFiles.CHIP_READS, backupWriter)

    init {
        cutoffs.whenANewCompetitionStarts(::startOver)
    }

    /** `start.txt` as last read; null before the first read. */
    val protocol: StateFlow<StartProtocol?> = protocolState.asStateFlow()

    /** True once the raw reads file missed lines, until a new competition; the reads are in Room. */
    val rawFailed: StateFlow<Boolean> = raw.failed

    // The chips with waiting reads, as ChronometerPython keeps its runs: read from Room once a
    // map reload and kept up to date with each batch, so no batch costs a query over them all.
    private val waiting = MutableStateFlow<Map<String, ChipSeen>>(emptyMap())

    /** Chips read that the map neither binds nor names as a conflict (FR-011), by id. */
    val unknown: Flow<List<ChipSeen>> =
        combine(waiting, protocolState) { seen, protocol ->
            val map = protocol?.map ?: ChipMap()
            seen.values.filter { it.chip !in map.bibOf && it.chip !in map.conflicts }.sortedBy { it.chip }
        }

    /**
     * A new competition (FR-016): no map until `start.txt` is read again, as it was moved
     * aside, and the raw reads file starts afresh, so its last failure is no more.
     */
    private fun startOver() {
        protocolState.value = StartProtocol.Missing()
        waiting.value = emptyMap()
        raw.startOver()
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
     * A `start.txt` missing or unreadable leaves the map in use, unless [keepMap] is false: in
     * another data folder, another event's, no map is the only right one.
     */
    suspend fun reloadMap(keepMap: Boolean = true) {
        // Read under the lock too, so reloads take effect in the order they read the file.
        CutoffRepository.mutationMutex.withLock { reloadUnderLock(keepMap) }
    }

    /** [reloadMap]'s work, for a caller that holds [CutoffRepository.mutationMutex]. */
    private suspend fun reloadUnderLock(keepMap: Boolean = true) {
        val before = protocolState.value?.map?.takeIf { keepMap } ?: ChipMap()
        val settings = settingsStore.load()
        val folder = settings.folderPath
        val protocol =
            withContext(Dispatchers.IO) {
                try {
                    StartProtocolFile.read(folder)?.let { StartProtocol.Loaded(ChipMap.of(it)) }
                        // Gone a moment, as a file manager replaces it: the map before binds on.
                        ?: StartProtocol.Missing(before)
                } catch (e: IOException) {
                    // A file being copied in, a storage hiccup: the map read before still binds.
                    StartProtocol.Unreadable(e.message ?: e.javaClass.simpleName, before)
                } catch (e: SecurityException) {
                    // No permission to read it: unreadable too, not a failure of every batch.
                    StartProtocol.Unreadable(e.message ?: e.javaClass.simpleName, before)
                }
            }
        val lastCutoff = db.withTransaction { timeWaitingReads(protocol.map, settings) }
        // Only once the reads it binds are timed: a reload cut short leaves the map before it.
        protocolState.value = protocol
        waiting.value = db.chipDao().waitingSummary().associateBy { it.chip }
        lastCutoff?.let { cutoffs.afterChange(it) }
    }

    /**
     * "Download start protocol" (FR-009): the site's start list replaces start.txt, the old
     * one kept in `backup/`, and its map is used at once. Asked only from the button: nothing
     * else in the app contacts the site for it (SC-005).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun download(client: StartListClient = StartListClient()): Download {
        val settings = settingsStore.load()
        if (!hasAllowedUploadScheme(settings.siteUrl) || settings.token.isBlank()) {
            return Download.Failed(DownloadProblem.NO_SITE)
        }
        // Asked in this competition: a new one started meanwhile has a start list of its own.
        val askedIn = cutoffs.competition
        return try {
            val lines = withContext(Dispatchers.IO) { client.fetch(settings.siteUrl, settings.token) }
            // Once fetched, the file and the map change together, even when the screen that
            // asked is gone: a new start.txt with the old map in use would bind no new chip.
            // Under the lock, so a new competition comes wholly before or after them.
            withContext(NonCancellable) {
                CutoffRepository.mutationMutex.withLock {
                    if (askedIn != cutoffs.competition) {
                        return@withLock Download.Failed(DownloadProblem.STALE)
                    }
                    val written =
                        withContext(Dispatchers.IO) {
                            StartProtocolFile.replace(settingsStore.load().folderPath, lines, now(), backupWriter)
                        }
                    if (written) {
                        reloadUnderLock()
                        // The file just written, read back: any other map in use is not its.
                        when (val read = protocolState.value) {
                            is StartProtocol.Loaded -> Download.Done(lines.size, read.map)
                            is StartProtocol.Unreadable -> Download.Failed(DownloadProblem.UNUSABLE, read.reason)
                            else -> Download.Failed(DownloadProblem.FILE)
                        }
                    } else {
                        Download.Failed(DownloadProblem.FILE)
                    }
                }
            }
        } catch (e: StartListException) {
            Download.Failed(e.problem, e.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Room failing as the map is used, say: said on screen, never the end of the app.
            Log.w(TAG, "start list downloaded, its map not used", e)
            Download.Failed(DownloadProblem.UNUSABLE, e.message ?: e.javaClass.simpleName)
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
        val deadMs = TimeUnit.SECONDS.toMillis(settings.deadTimeS.toLong())
        val book = PassBook(db.passDao(), deadMs)
        val made = mutableListOf<ChipReadEntity>()
        for ((bib, ofBib) in reads.groupBy { map.bibOf.getValue(it.chip) }) {
            // Grouped first, as ChronometerPython's set_map: a group near a pass makes nothing,
            // however much earlier its first read is.
            for (group in groups(ofBib.sortedWith(compareBy({ it.timeMs }, { it.id })), deadMs)) {
                if (book.join(bib, group.first().timeMs, group.last().timeMs)) {
                    made += group.first().copy(bib = bib)
                }
            }
        }
        book.save()
        bound.forEach { dao.attribute(it, map.bibOf.getValue(it)) }
        return made
            .sortedBy { it.timeMs }
            .map { db.cutoffDao().insert(cutoffOf(checkNotNull(it.bib), it.timeStr, settings.finishMode)) }
            .lastOrNull()
    }

    /**
     * Store [reads] in arrival order and give each read of a mapped chip to its bib's passes;
     * a read that starts a pass makes its cutoff (spec FR-007, FR-010, FR-012). Reads, passes
     * and cutoffs commit together, then the cutoffs take the durable path once for the batch
     * and the raw reads file gets the lines.
     */
    suspend fun apply(
        reads: List<SourceEvent.ChipRead>,
        /** The competition they were taken in: dropped once a new one has started. */
        takenIn: Long = cutoffs.competition,
    ) {
        if (reads.isEmpty()) {
            return
        }
        ensureMap()
        CutoffRepository.mutationMutex.withLock {
            if (takenIn != cutoffs.competition) {
                return@withLock // the last competition's: its tables are gone
            }
            // Read now, not before waiting: a save meanwhile counts for this batch.
            val settings = settingsStore.load()
            val map = protocolState.value?.map ?: ChipMap()
            val rows = reads.map { read -> rowOf(read, map) }
            db.withTransaction { storeAndTime(rows, settings) }?.let { cutoffs.afterChange(it) }
            waiting.value = withWaiting(waiting.value, rows.filter { it.bib == null })
            val lines =
                rows.map {
                    "${it.chip}#${it.timeStr}#${it.deviceTime}#${it.antenna}#${RawFiles.signal(it.rssi)}#${it.reader}#"
                }
            // Under the lock too: a new competition moves the file aside before or after it.
            raw.append(settings.folderPath, lines)
        }
    }

    /** [rows] stored, and each read of a bound chip into its bib's passes; the last cutoff's id. */
    private suspend fun storeAndTime(
        rows: List<ChipReadEntity>,
        settings: Settings,
    ): Long? {
        db.chipDao().insertReads(rows)
        val book = PassBook(db.passDao(), TimeUnit.SECONDS.toMillis(settings.deadTimeS.toLong()))
        var last: Long? = null
        for (row in rows) {
            val bib = row.bib ?: continue
            if (book.join(bib, row.timeMs, row.timeMs)) {
                last = db.cutoffDao().insert(cutoffOf(bib, row.timeStr, settings.finishMode))
            }
        }
        book.save()
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
 * The passes of the bibs a batch touches, read once and kept in memory; [save] writes back
 * what changed, inside the batch's transaction. The pass rule (spec FR-010,
 * ChronometerPython's `_into_passes`): reads from first to last less than the dead time from
 * passes of their bib join them, merging them into one, and make nothing; otherwise they are a
 * new pass. A cutoff is never moved, and a pass whose cutoff the referee deleted or edited
 * still holds its reads.
 */
private class PassBook(
    private val dao: PassDao,
    private val deadMs: Long,
) {
    private val read = mutableMapOf<String, List<PassEntity>>()
    private val now = mutableMapOf<String, MutableList<PassEntity>>()

    /** Put reads from [first] to [last] into [bib]'s passes; true for a new pass. */
    suspend fun join(
        bib: String,
        first: Long,
        last: Long,
    ): Boolean {
        val passes = now.getOrPut(bib) { dao.passesOf(bib).also { read[bib] = it }.toMutableList() }
        val near = passes.filter { it.firstMs - deadMs < last && first < it.lastMs + deadMs }
        passes.removeAll { pass -> near.any { it === pass } }
        // Kept under a stored id where there is one, so fewer rows change.
        val kept =
            near.firstOrNull { it.id != 0L } ?: near.firstOrNull()
                ?: PassEntity(bib = bib, firstMs = first, lastMs = last)
        passes +=
            kept.copy(
                firstMs = minOf(first, near.minOfOrNull { it.firstMs } ?: first),
                lastMs = maxOf(last, near.maxOfOrNull { it.lastMs } ?: last),
            )
        return near.isEmpty()
    }

    suspend fun save() {
        for ((bib, passes) in now) {
            val before = read.getValue(bib).associateBy { it.id }
            passes.filter { it.id == 0L }.forEach { dao.insertPass(it) }
            passes.filter { it.id != 0L && it != before[it.id] }.forEach { dao.updatePass(it) }
            val gone = before.keys - passes.map { it.id }.toSet()
            if (gone.isNotEmpty()) {
                dao.deletePasses(gone.toList())
            }
        }
    }
}

/** [seen] with [reads] of chips no map binds added in: first and last read, and count. */
private fun withWaiting(
    seen: Map<String, ChipSeen>,
    reads: List<ChipReadEntity>,
): Map<String, ChipSeen> {
    if (reads.isEmpty()) {
        return seen
    }
    val next = seen.toMutableMap()
    for (read in reads) {
        val was = next[read.chip]
        next[read.chip] =
            was?.copy(
                firstMs = minOf(was.firstMs, read.timeMs),
                lastMs = maxOf(was.lastMs, read.timeMs),
                reads =
                    was.reads + 1,
            )
                ?: ChipSeen(read.chip, read.timeMs, read.timeMs, 1)
    }
    return next
}
