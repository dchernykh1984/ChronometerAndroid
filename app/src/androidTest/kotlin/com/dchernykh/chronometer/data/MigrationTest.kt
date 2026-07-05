package com.dchernykh.chronometer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun migrate1To2KeepsCutoffsAndMapsDisqualified() {
        val name = "migration-test"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO cutoffs (id, number, timeStr, disqualified, createdAt) " +
                    "VALUES (1, '42', '1 2:3:4.005', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO cutoffs (id, number, timeStr, disqualified, createdAt) " +
                    "VALUES (2, '7', '1 2:3:5.000', 1, 200)",
            )
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT number, event FROM cutoffs ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("42", cursor.getString(0))
                assertEquals("nextLap", cursor.getString(1))
                cursor.moveToNext()
                assertEquals("7", cursor.getString(0))
                assertEquals("DSQ", cursor.getString(1))
            }
        }
    }
}
