package com.dchernykh.chronometer.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Cutoffs must survive the app being closed and reopened (not just minimized).
 * A file-backed Room database that is closed and reopened is exactly that: a
 * process restart reading the same on-disk file.
 */
@RunWith(RobolectricTestRunner::class)
class CutoffPersistenceTest {
    @Test
    fun cutoffsSurviveDatabaseReopen() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val name = "persist-${System.nanoTime()}.db"

            val first =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
            first.cutoffDao().insert(CutoffEntity(number = "7", timeStr = "t", event = "nextLap", createdAt = 1))
            first.close()

            val reopened =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
            val all = reopened.cutoffDao().getAll()
            reopened.close()
            context.getDatabasePath(name).delete()

            assertEquals(1, all.size)
            assertEquals("7", all[0].number)
        }
}
