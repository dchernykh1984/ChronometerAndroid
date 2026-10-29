package com.dchernykh.chronometer.data

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SourceDaosTest {
    private lateinit var db: AppDatabase
    private lateinit var chips: ChipDao
    private lateinit var passes: PassDao
    private lateinit var beam: BeamDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        chips = db.chipDao()
        passes = db.passDao()
        beam = db.beamDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun read(
        chip: String,
        timeMs: Long,
    ) = ChipReadEntity(chip = chip, timeStr = "t$timeMs", timeMs = timeMs)

    @Test
    fun waitingReadsAreListedByChipWithFirstLastAndCount() =
        runTest {
            chips.insertReads(listOf(read("A", 5), read("B", 9), read("A", 1), read("A", 7)))
            assertEquals(1, chips.attribute("B", "15"))
            assertEquals(listOf(ChipSeen("A", 1, 7, 3)), chips.observeWaiting().first())
            assertEquals(listOf("A"), chips.waitingChips())
            assertEquals(listOf(1L, 5L, 7L), chips.waitingReadsOf(listOf("A", "B")).map { it.timeMs })
            // A chip's reads wait one by one: a read after a map dropped it waits again.
            chips.insertReads(listOf(read("B", 20)))
            assertEquals(listOf(ChipSeen("A", 1, 7, 3), ChipSeen("B", 20, 20, 1)), chips.observeWaiting().first())
        }

    @Test
    fun passesComeInTimeOrderAndChange() =
        runTest {
            val late = passes.insertPass(PassEntity(bib = "15", firstMs = 90, lastMs = 95))
            val early = passes.insertPass(PassEntity(bib = "15", firstMs = 10, lastMs = 20))
            passes.insertPass(PassEntity(bib = "16", firstMs = 0, lastMs = 0))
            assertEquals(listOf(early, late), passes.passesOf("15").map { it.id })
            passes.updatePass(passes.passesOf("15").first().copy(lastMs = 30))
            passes.deletePasses(listOf(late))
            assertEquals(listOf(10L to 30L), passes.passesOf("15").map { it.firstMs to it.lastMs })
        }

    @Test
    fun beamBreaksWaitOldestFirst() =
        runTest {
            val first = beam.insertBreak(PendingBreakEntity(seq = 1, timeStr = "a"))
            beam.insertBreak(PendingBreakEntity(seq = 2, timeStr = "b"))
            assertEquals(first, beam.oldestBreak()?.id)
            assertEquals(1, beam.deleteBreak(first))
            assertEquals(listOf(2L), beam.observeBreaks().first().map { it.seq })
            beam.clearBreaks()
            assertNull(beam.oldestBreak())
        }

    @Test
    fun aNewCompetitionClearsEveryTable() =
        runTest {
            chips.insertReads(listOf(read("A", 1)))
            passes.insertPass(PassEntity(bib = "15", firstMs = 1, lastMs = 1))
            chips.clearReads()
            passes.clearPasses()
            assertEquals(emptyList<ChipSeen>(), chips.observeWaiting().first())
            assertEquals(emptyList<PassEntity>(), passes.passesOf("15"))
        }
}
