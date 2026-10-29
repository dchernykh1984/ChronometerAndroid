package com.dchernykh.chronometer.beam

import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.source.SourceEvent.BeamBreak
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

private const val T0 = 1_767_261_600_123L

@RunWith(RobolectricTestRunner::class)
class BeamRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var folder: File
    private lateinit var cutoffs: CutoffRepository

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = SettingsStore(context)
        folder = File(context.cacheDir, "beam-${System.nanoTime()}")
        store.save(store.load().copy(folderPath = folder.path))
        cutoffs = CutoffRepository(context, db, store, BackupWriter())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun beam(database: AppDatabase = db) = BeamRepository(database, store, BackupWriter(), cutoffs)

    private fun rawLines() = File(folder, "beam_breaks.txt").readLines()

    private fun time(ms: Long) = TimeFormatter.currentTimeString(ms)

    @Test
    fun aBreakWaitsInRoomAndItsLineGoesToTheRawFile() =
        runTest {
            val beam = beam()
            beam.add(listOf(BeamBreak(7, T0, "dev#1\n2")))
            assertEquals(listOf(7L), beam.pending.first().map { it.seq })
            assertEquals(listOf("7#${time(T0)}#dev 1 2#"), rawLines())
            assertFalse(beam.rawFailed.value)
        }

    @Test
    fun theOldestBreakTakesTheNumberAtItsTimeWithTheGivenEvent() =
        runTest {
            val beam = beam()
            beam.add(listOf(BeamBreak(1, T0), BeamBreak(2, T0 + 1_500)))
            assertTrue(beam.assignOldest(" 1#5 ", CutoffEvent.FINISH))
            val cutoff = db.cutoffDao().getAll().single()
            assertEquals("1 5", cutoff.number)
            assertEquals(time(T0), cutoff.timeStr)
            assertEquals(CutoffEvent.FINISH, cutoff.event)
            assertEquals(listOf(2L), beam.pending.first().map { it.seq })
            assertTrue(File(folder, "results.txt").readText().contains("1 5#${time(T0)}#"))
        }

    @Test
    fun nothingIsAssignedWithoutABreakOrWithABlankNumber() =
        runTest {
            val beam = beam()
            assertFalse(beam.assignOldest("15", CutoffEvent.FINISH))
            beam.add(listOf(BeamBreak(1, T0)))
            assertFalse(beam.assignOldest("  ", CutoffEvent.FINISH))
            assertEquals(1, beam.pending.first().size)
            assertTrue(db.cutoffDao().getAll().isEmpty())
        }

    @Test
    fun aDiscardedBreakLeavesTheQueueAndStaysInTheRawFile() =
        runTest {
            val beam = beam()
            beam.add(listOf(BeamBreak(1, T0)))
            beam.discard(
                beam.pending
                    .first()
                    .single()
                    .id,
            )
            assertTrue(beam.pending.first().isEmpty())
            assertEquals(1, rawLines().size)
            assertFalse(beam.assignOldest("15", CutoffEvent.FINISH))
        }

    @Test
    fun aRawFileThatCannotBeWrittenIsFlaggedAndTheBreakStillWaits() =
        runTest {
            val blocker = File(context.cacheDir, "blocker-${System.nanoTime()}").apply { writeText("") }
            store.save(store.load().copy(folderPath = File(blocker, "data").path))
            val beam = beam()
            beam.add(listOf(BeamBreak(1, T0)))
            assertTrue(beam.rawFailed.value)
            assertEquals(1, beam.pending.first().size)
            store.save(store.load().copy(folderPath = folder.path))
            assertTrue(cutoffs.startNewCompetition())
            assertFalse(beam.rawFailed.value)
        }

    @Test
    fun aBreakTakenBeforeANewCompetitionIsDroppedUnderTheLock() =
        runTest {
            val beam = beam()
            val takenIn = cutoffs.competition
            assertTrue(cutoffs.startNewCompetition())
            beam.add(listOf(BeamBreak(1, T0)), takenIn)
            assertTrue(beam.pending.first().isEmpty())
            assertFalse(File(folder, "beam_breaks.txt").exists())
        }

    @Test
    fun pendingBreaksOutliveTheProcess() =
        runTest {
            val name = "beam-${System.nanoTime()}.db"

            fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
            val before = open()
            beam(before).add(listOf(BeamBreak(1, T0)))
            before.close()
            val after = open()
            try {
                assertEquals(listOf(time(T0)), beam(after).pending.first().map { it.timeStr })
            } finally {
                after.close()
            }
        }
}
