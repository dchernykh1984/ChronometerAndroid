package com.dchernykh.chronometer.data

import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.io.BackupWriter
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

@RunWith(RobolectricTestRunner::class)
class CutoffRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var repository: CutoffRepository
    private lateinit var folder: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = SettingsStore(context)
        folder = File(context.cacheDir, "backup-${System.nanoTime()}")
        repository = CutoffRepository(context, db.cutoffDao(), store, BackupWriter())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun configure(
        sendEnabled: Boolean,
        folderPath: String = folder.path,
    ) {
        store.save(
            Settings(
                siteUrl = "http://site",
                token = "tok",
                pointNumber = 0,
                deviceUuid = store.load().deviceUuid,
                folderPath = folderPath,
                sendEnabled = sendEnabled,
            ),
        )
    }

    private fun uploadWork() =
        WorkManager
            .getInstance(RuntimeEnvironment.getApplication())
            .getWorkInfosForUniqueWork("chronometer_upload")
            .get()

    @Test
    fun recordInsertsIntoRoomAndWritesBackup() =
        runTest {
            configure(sendEnabled = false)
            repository.record("42", CutoffEvent.NEXT_LAP)

            val all = db.cutoffDao().getAll()
            assertEquals(1, all.size)
            assertEquals("42", all[0].number)
            assertEquals(CutoffEvent.NEXT_LAP, all[0].event)
            assertTrue(File(folder, "results.txt").readText().contains("42#"))
            assertFalse(repository.backupFailed.value)
        }

    @Test
    fun recordEnqueuesUploadWhenConfigured() =
        runTest {
            configure(sendEnabled = true)
            repository.record("7", CutoffEvent.NEXT_LAP)
            assertTrue(uploadWork().isNotEmpty())
        }

    @Test
    fun recordDoesNotEnqueueWhenSendDisabled() =
        runTest {
            configure(sendEnabled = false)
            repository.record("7", CutoffEvent.NEXT_LAP)
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun blankNumberIsIgnored() =
        runTest {
            configure(sendEnabled = false)
            repository.record("   ", CutoffEvent.NEXT_LAP)
            assertTrue(db.cutoffDao().getAll().isEmpty())
        }

    @Test
    fun backupFailureIsSurfacedButRoomStillHasTheCutoff() =
        runTest {
            val blocker = File(RuntimeEnvironment.getApplication().cacheDir, "blocker-${System.nanoTime()}")
            blocker.writeText("x")
            configure(sendEnabled = false, folderPath = File(blocker, "sub").path)
            repository.record("9", CutoffEvent.NEXT_LAP)

            assertTrue(repository.backupFailed.value)
            assertEquals(1, db.cutoffDao().getAll().size)
        }
}
