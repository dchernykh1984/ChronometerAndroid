package com.dchernykh.chronometer.chips

import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.source.SourceEvent.ChipRead
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

private const val BASE_MS = 1_767_261_600_000L

@RunWith(RobolectricTestRunner::class)
class ChipRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var folder: File

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = SettingsStore(context)
        folder = File(context.cacheDir, "chips-${System.nanoTime()}").apply { mkdirs() }
        store.save(store.load().copy(folderPath = folder.path, deadTimeS = 5, finishMode = false))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cutoffs(database: AppDatabase = db) =
        CutoffRepository(context, database.cutoffDao(), store, BackupWriter())

    private fun chips(database: AppDatabase = db) = ChipRepository(database, store, BackupWriter(), cutoffs(database))

    /** One line a bib, from 15 on, binding the given chips. */
    private fun startTxt(vararg chipFields: String) =
        File(folder, "start.txt").writeText(
            chipFields.withIndex().joinToString(
                "",
            ) { (i, chips) -> "${15 + i}#R#M#3#1#1990#T#C##0 0:0:0.000#r#$chips#\n" },
        )

    private fun read(
        chip: String,
        atMs: Long,
    ) = ChipRead(chip, BASE_MS + atMs)

    private fun time(atMs: Long) = TimeFormatter.currentTimeString(BASE_MS + atMs)

    private suspend fun made(database: AppDatabase = db) = database.cutoffDao().getAll().map { it.number to it.timeStr }

    private fun rawLines() = File(folder, "chip_reads.txt").readLines()

    @Test
    fun startTxtIsReadOnceAtStartUpAndAgainOnReload() =
        runTest {
            val chips = chips()
            assertNull(chips.protocol.value)
            startTxt("A1,A2")
            chips.ensureMap()
            assertEquals(
                mapOf("A1" to "15", "A2" to "15"),
                chips.protocol.value!!
                    .map.bibOf,
            )
            startTxt("A1", "B1")
            chips.ensureMap()
            assertEquals(
                1,
                chips.protocol.value!!
                    .map.bibs,
            )
            chips.reloadMap()
            assertEquals(
                2,
                chips.protocol.value!!
                    .map.bibs,
            )
        }

    @Test
    fun withoutStartTxtTheMapIsEmptyAndSaysSo() =
        runTest {
            val chips = chips()
            chips.reloadMap()
            assertEquals(StartProtocol.Missing, chips.protocol.value)
            assertTrue(
                chips.protocol.value!!
                    .map.bibOf
                    .isEmpty(),
            )
        }

    @Test
    fun aStartTxtThatCannotBeReadIsToldFromAMissingOne() =
        runTest {
            File(folder, "start.txt").writeText("15#")
            File(folder, "start.txt").setReadable(false)
            val chips = chips()
            chips.reloadMap()
            val read = chips.protocol.value
            if (File(folder, "start.txt").canRead()) {
                return@runTest // running as root reads every file
            }
            assertTrue(read.toString(), read is StartProtocol.Unreadable)
            assertTrue(read!!.map.bibOf.isEmpty())
        }

    @Test
    fun aReadIsKeptWithWhatTheDeviceGaveAndItsPassMakesACutoff() =
        runTest {
            startTxt("A1")
            chips().apply(
                listOf(ChipRead("a-1", BASE_MS, deviceTime = "d#1", antenna = "2", rssi = -63.0, reader = "r\n1")),
            )
            assertEquals(listOf("15" to time(0)), made())
            assertEquals(
                CutoffEvent.NEXT_LAP,
                db
                    .cutoffDao()
                    .getAll()
                    .single()
                    .event,
            )
            assertEquals(listOf("A1#${time(0)}#d 1#2#-63#r 1#"), rawLines())
            assertTrue(File(folder, "results.txt").readText().contains("15#${time(0)}#"))
        }

    @Test
    fun readsOfUnknownChipsAreKeptAndTimeNothing() =
        runTest {
            startTxt("A1", "B1,X9", "X9")
            val chips = chips()
            chips.apply(listOf(read("Q7", 0), read("x9", 100)))
            assertEquals(emptyList<Pair<String, String>>(), made())
            assertEquals(2, rawLines().size)
            assertEquals(listOf("Q7", "X9"), db.chipDao().waitingChips().sorted())
            assertFalse(chips.rawFailed.value)
        }

    @Test
    fun finishModeMakesFinishCutoffs() =
        runTest {
            startTxt("A1")
            store.save(store.load().copy(finishMode = true))
            chips().apply(listOf(read("A1", 0)))
            assertEquals(
                CutoffEvent.FINISH,
                db
                    .cutoffDao()
                    .getAll()
                    .single()
                    .event,
            )
        }

    @Test
    fun aRestartWithinTheDeadTimeMakesNoSecondCutoff() =
        runTest {
            startTxt("A1")
            val name = "chips-${System.nanoTime()}.db"

            fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
            val before = open()
            chips(before).apply(listOf(read("A1", 0)))
            before.close()
            val after = open()
            try {
                chips(after).apply(listOf(read("A1", 3_000)))
                assertEquals(listOf("15" to time(0)), made(after))
            } finally {
                after.close()
            }
        }

    @Test
    fun aDeletedChipCutoffStaysDeletedAndTheNextPassIsTimed() =
        runTest {
            startTxt("A1")
            val cutoffs = cutoffs()
            val chips = ChipRepository(db, store, BackupWriter(), cutoffs)
            chips.apply(listOf(read("A1", 0)))
            assertTrue(
                cutoffs.deleteCutoff(
                    db
                        .cutoffDao()
                        .getAll()
                        .single()
                        .id,
                ),
            )
            chips.apply(listOf(read("A1", 2_000)))
            assertEquals(emptyList<Pair<String, String>>(), made())
            chips.apply(listOf(read("A1", 9_000)))
            assertEquals(listOf("15" to time(9_000)), made())
        }

    @Test
    fun anEditedChipCutoffMakesNoNewOneForTheOldBib() =
        runTest {
            startTxt("A1")
            val cutoffs = cutoffs()
            val chips = ChipRepository(db, store, BackupWriter(), cutoffs)
            chips.apply(listOf(read("A1", 0)))
            assertTrue(
                cutoffs.editCutoff(
                    db
                        .cutoffDao()
                        .getAll()
                        .single()
                        .id,
                    "16",
                    time(0),
                ),
            )
            chips.apply(listOf(read("A1", 2_000)))
            assertEquals(listOf("16" to time(0)), made())
        }

    @Test
    fun aBurstOf10000ReadsIsStoredInOrderWithNoneLost() =
        runTest {
            startTxt(*Array(50) { "C$it" })
            val chips = chips()
            val reads = List(10_000) { read("C${it % 50}", it * 10L) }
            reads.chunked(200).forEach { chips.apply(it) }
            val stored = mutableListOf<String>()
            db.openHelper.readableDatabase.query("SELECT chip, timeStr FROM chip_reads ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    stored += "${cursor.getString(0)}#${cursor.getString(1)}"
                }
            }
            val expected = reads.map { "${it.chip}#${time(it.timeMs!! - BASE_MS)}" }
            assertEquals(expected, stored)
            assertEquals(expected, rawLines().map { it.split('#').take(2).joinToString("#") })
            assertEquals(50, made().size)
        }
}
