package com.dchernykh.chronometer.data

import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.shadows.ShadowLog
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
        repository = CutoffRepository(context, db, store, BackupWriter())
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
    fun recordBumpsTheRevision() =
        runTest {
            configure(sendEnabled = false)
            val revision = store.clientRevision()
            repository.record("15", CutoffEvent.FINISH)
            assertEquals(revision + 1, store.clientRevision())
        }

    @Test
    fun recordUsesTheMomentOfTheTapItIsGiven() =
        runTest {
            configure(sendEnabled = false)
            val tappedAt = System.currentTimeMillis() - 1_500
            repository.record("15", CutoffEvent.NEXT_LAP, tappedAt)
            assertEquals(
                TimeFormatter.currentTimeString(tappedAt),
                db
                    .cutoffDao()
                    .getAll()
                    .single()
                    .timeStr,
            )
        }

    @Test
    fun aCutoffStoredBeforeItsSnapshotFailsStaysStoredAndSaysSo() =
        runTest {
            configure(sendEnabled = false)
            val brokenAfterInsert =
                object : CutoffDao by db.cutoffDao() {
                    override suspend fun getAll(): List<CutoffEntity> = error("disk I/O error")
                }
            CutoffRepository(RuntimeEnvironment.getApplication(), db, store, BackupWriter(), dao = brokenAfterInsert)
                .apply {
                    record("15", CutoffEvent.NEXT_LAP)
                    assertTrue(backupFailed.value)
                }
            assertEquals(listOf("15"), db.cutoffDao().getAll().map { it.number })
            assertTrue(ShadowLog.getLogsForTag("CutoffRepository").any { it.throwable?.message == "disk I/O error" })
        }

    @Test
    fun aTypedHashOrLineBreakNeverReachesTheResultsLine() =
        runTest {
            configure(sendEnabled = false)
            repository.record("12#A\n7", CutoffEvent.NEXT_LAP)
            assertEquals(
                "12 A 7",
                db
                    .cutoffDao()
                    .getAll()
                    .single()
                    .number,
            )
            assertTrue(File(folder, "results.txt").readText().startsWith("12 A 7#"))
        }

    @Test
    fun recordIgnoresABlankNumber() =
        runTest {
            configure(sendEnabled = false)
            repository.record("  ", CutoffEvent.NEXT_LAP)
            assertTrue(db.cutoffDao().getAll().isEmpty())
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
    fun editUpdatesNumberAndTimeKeepsEventAndAddsSnapshotWithoutLosingTheOriginal() =
        runTest {
            configure(sendEnabled = false)
            repository.record("42", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            val backupBefore = File(folder, "backup").listFiles()?.size ?: 0

            assertTrue(repository.editCutoff(row.id, "43", "1 2:3:4.005"))

            val updated = db.cutoffDao().getAll().single()
            assertEquals("43", updated.number)
            assertEquals("1 2:3:4.005", updated.timeStr)
            assertEquals(CutoffEvent.NEXT_LAP, updated.event)
            assertTrue(File(folder, "results.txt").readText().contains("43#1 2:3:4.005#nextLap#"))
            // A fresh snapshot was added; the original snapshot file is preserved.
            assertTrue((File(folder, "backup").listFiles()?.size ?: 0) > backupBefore)
            assertTrue(File(folder, "backup/${row.id}.txt").exists())
            assertFalse(repository.backupFailed.value)
        }

    @Test
    fun editRejectsInvalidTimeAndLeavesRowUnchanged() =
        runTest {
            configure(sendEnabled = false)
            repository.record("42", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()

            assertFalse(repository.editCutoff(row.id, "43", "not a time"))

            val after = db.cutoffDao().getAll().single()
            assertEquals("42", after.number)
            assertEquals(row.timeStr, after.timeStr)
        }

    @Test
    fun editRejectsBlankNumberAndLeavesRowUnchanged() =
        runTest {
            configure(sendEnabled = false)
            repository.record("42", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()

            assertFalse(repository.editCutoff(row.id, "   ", "1 2:3:4.005"))

            val after = db.cutoffDao().getAll().single()
            assertEquals("42", after.number)
        }

    @Test
    fun editStripsHashFromNumberSoItCannotBreakTheRecord() =
        runTest {
            configure(sendEnabled = false)
            repository.record("42", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()

            assertTrue(repository.editCutoff(row.id, "4#3", "1 2:3:4.005"))

            val edited = db.cutoffDao().getAll().single()
            assertEquals("4 3", edited.number)
        }

    @Test
    fun editEnqueuesUploadWhenConfigured() =
        runTest {
            configure(sendEnabled = true)
            repository.record("7", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()

            assertTrue(repository.editCutoff(row.id, "8", "1 2:3:4.005"))

            assertTrue(uploadWork().isNotEmpty())
        }

    @Test
    fun deletePreservesOriginalBackupsAndAddsCompleteBeforeAndAfterSnapshots() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            repository.record("22", CutoffEvent.FINISH)
            val rows = db.cutoffDao().getAll()
            val originals = File(folder, "backup").listFiles()!!.associate { it.name to it.readText() }
            val revision = store.clientRevision()
            val before = rows.joinToString("\n", postfix = "\n") { it.toItem() }
            val after = rows.last().toItem() + "\n"

            assertTrue(repository.deleteCutoff(rows.first().id))

            assertEquals(listOf(rows.last()), db.cutoffDao().getAll())
            assertEquals(after, File(folder, "results.txt").readText())
            assertEquals(before, File(folder, "backup/${rows.first().id}-1.txt").readText())
            assertEquals(after, File(folder, "backup/${rows.first().id}-2.txt").readText())
            val snapshots = File(folder, "backup").listFiles()!!.associate { it.name to it.readText() }
            assertEquals(originals.size + 2, snapshots.size)
            originals.forEach { (name, body) -> assertEquals(body, snapshots[name]) }
            assertEquals(revision + 1, store.clientRevision())
            assertFalse(repository.backupFailed.value)
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun deleteIsBlockedIfTheBackupCannotBeSavedEvenWhenResultsAreWritable() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            val revision = store.clientRevision()
            val backup = File(folder, "backup")
            assertTrue(backup.renameTo(File(folder, "preserved-backups")))
            backup.writeText("block directory creation")
            configure(sendEnabled = true)

            assertFalse(repository.deleteCutoff(row.id))

            assertEquals(listOf(row), db.cutoffDao().getAll())
            assertEquals(revision, store.clientRevision())
            assertFalse(store.allowsEmptySnapshot())
            assertTrue(repository.backupFailed.value)
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun deletingAMissingRowDoesNotChangeFilesRevisionOrUploadIntent() =
        runTest {
            configure(sendEnabled = true)
            val revision = store.clientRevision()
            assertFalse(repository.deleteCutoff(123))
            assertFalse(folder.exists())
            assertEquals(revision, store.clientRevision())
            assertFalse(store.allowsEmptySnapshot())
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun postDeleteBackupFailureKeepsTheRecoverySnapshotAndQueuesTheDurableDeletion() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            val dao = db.cutoffDao()
            val failingAfterDelete =
                object : CutoffDao by dao {
                    override suspend fun deleteById(id: Long): Int {
                        val deleted = dao.deleteById(id)
                        // Simulate storage becoming unavailable after the durable deletion.
                        assertTrue(File(folder, "backup").renameTo(File(folder, "recovery")))
                        File(folder, "backup").writeText("blocked")
                        return deleted
                    }
                }
            configure(sendEnabled = true)
            val deleting =
                CutoffRepository(
                    RuntimeEnvironment.getApplication(),
                    db,
                    store,
                    BackupWriter(),
                    dao = failingAfterDelete,
                )

            assertTrue(deleting.deleteCutoff(row.id))

            assertTrue(db.cutoffDao().getAll().isEmpty())
            assertTrue(File(folder, "recovery/${row.id}-1.txt").readText().contains("11#"))
            assertEquals("", File(folder, "results.txt").readText())
            assertTrue(deleting.backupFailed.value)
            assertTrue(uploadWork().isNotEmpty())
        }

    @Test
    fun deletingTheLastCutoffWhileSendingIsOffCanBeUploadedAfterRestart() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            assertTrue(repository.deleteCutoff(row.id))
            assertEquals("", File(folder, "results.txt").readText())
            assertEquals("", File(folder, "backup/${row.id}-2.txt").readText())
            assertTrue(uploadWork().isEmpty())

            val context = RuntimeEnvironment.getApplication()
            val reopenedStore = SettingsStore(context)
            assertTrue(reopenedStore.allowsEmptySnapshot())
            configure(sendEnabled = true)
            CutoffRepository(context, db, reopenedStore, BackupWriter()).uploadPendingCutoffs()
            assertTrue(uploadWork().isNotEmpty())
        }

    @Test
    fun newCompetitionDoesNotUploadThePreviousEmptySnapshot() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            assertTrue(
                repository.deleteCutoff(
                    db
                        .cutoffDao()
                        .getAll()
                        .single()
                        .id,
                ),
            )
            assertTrue(repository.startNewCompetition())
            configure(sendEnabled = true)
            repository.uploadPendingCutoffs()
            assertFalse(store.allowsEmptySnapshot())
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun cancellationAfterTheDatabaseDeleteStillFinishesThePostDeleteBackupAndUpload() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            val revision = store.clientRevision()
            val deleted = CompletableDeferred<Unit>()
            val finishDelete = CompletableDeferred<Unit>()
            val dao = db.cutoffDao()
            val pausedDao =
                object : CutoffDao by dao {
                    override suspend fun deleteById(id: Long): Int {
                        val count = dao.deleteById(id)
                        deleted.complete(Unit)
                        finishDelete.await()
                        return count
                    }
                }
            configure(sendEnabled = true)
            val deleting =
                CutoffRepository(RuntimeEnvironment.getApplication(), db, store, BackupWriter(), dao = pausedDao)
            val task = launch { deleting.deleteCutoff(row.id) }
            deleted.await()
            task.cancel()
            finishDelete.complete(Unit)
            task.join()

            assertEquals(revision + 1, store.clientRevision())
            assertEquals("", File(folder, "results.txt").readText())
            assertEquals("", File(folder, "backup/${row.id}-2.txt").readText())
            assertTrue(uploadWork().isNotEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recordingDuringDeletionCannotOverwriteTheNewerResultsOrReuseABackupName() =
        runTest {
            configure(sendEnabled = false)
            repository.record("11", CutoffEvent.NEXT_LAP)
            val row = db.cutoffDao().getAll().single()
            val deletingRow = CompletableDeferred<Unit>()
            val resumeDeletion = CompletableDeferred<Unit>()
            val dao = db.cutoffDao()
            val pausedDao =
                object : CutoffDao by dao {
                    override suspend fun deleteById(id: Long): Int {
                        deletingRow.complete(Unit)
                        resumeDeletion.await()
                        return dao.deleteById(id)
                    }
                }
            val deleting =
                CutoffRepository(RuntimeEnvironment.getApplication(), db, store, BackupWriter(), dao = pausedDao)
            val delete = async { deleting.deleteCutoff(row.id) }
            deletingRow.await()
            val record = async { repository.record("22", CutoffEvent.FINISH) }
            runCurrent()
            assertFalse(record.isCompleted)
            resumeDeletion.complete(Unit)
            assertTrue(delete.await())
            record.await()

            val rows = db.cutoffDao().getAll()
            assertEquals("22", rows.single().number)
            assertEquals(rows.single().toItem() + "\n", File(folder, "results.txt").readText())
            assertEquals(4, File(folder, "backup").listFiles()!!.size)
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

    @Test
    fun uploadPendingEnqueuesWhenSettingsBecomeReadyWithExistingCutoffs() =
        runTest {
            configure(sendEnabled = false)
            repository.record("5", CutoffEvent.NEXT_LAP)
            assertTrue(uploadWork().isEmpty())

            configure(sendEnabled = true)
            repository.uploadPendingCutoffs()

            assertTrue(uploadWork().isNotEmpty())
        }

    @Test
    fun uploadPendingDoesNothingWithoutCutoffs() =
        runTest {
            configure(sendEnabled = true)
            repository.uploadPendingCutoffs()
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun uploadPendingDoesNothingWhenNotReady() =
        runTest {
            configure(sendEnabled = false)
            repository.record("5", CutoffEvent.NEXT_LAP)
            repository.uploadPendingCutoffs()
            assertTrue(uploadWork().isEmpty())
        }

    @Test
    fun startNewCompetitionClearsCutoffsAndResultsButKeepsBackups() =
        runTest {
            configure(sendEnabled = false)
            repository.record("1", CutoffEvent.NEXT_LAP)
            repository.record("2", CutoffEvent.NEXT_LAP)
            val backupCount = File(folder, "backup").listFiles()?.size ?: 0
            assertTrue(backupCount >= 2)

            assertTrue(repository.startNewCompetition())

            assertTrue(db.cutoffDao().getAll().isEmpty())
            assertEquals("", File(folder, "results.txt").readText())
            // Backups are preserved; the token is cleared and the point reset.
            assertEquals(backupCount, File(folder, "backup").listFiles()?.size ?: 0)
            assertEquals("", store.load().token)
            assertEquals(0, store.load().pointNumber)
        }

    @Test
    fun aNewCompetitionClearsWhatTheSourcesLeftAndMovesTheirFilesToBackup() =
        runTest {
            configure(sendEnabled = false)
            val names = listOf("start.txt", "chip_reads.txt", "beam_breaks.txt")
            folder.mkdirs()
            names.forEach { File(folder, it).writeText("$it#\n") }
            db.beamDao().insertBreak(PendingBreakEntity(seq = 1, timeStr = "t"))
            db.chipDao().insertReads(listOf(ChipReadEntity(chip = "A1", timeStr = "t", timeMs = 1)))
            db.passDao().insertPass(PassEntity(bib = "15", firstMs = 1, lastMs = 1))
            var cleared = 0

            repository.whenANewCompetitionStarts { cleared++ }
            assertTrue(repository.startNewCompetition())

            assertEquals(1, cleared)
            assertNull(db.beamDao().oldestBreak())
            assertTrue(db.chipDao().waitingChips().isEmpty())
            assertTrue(db.passDao().passesOf("15").isEmpty())
            for (name in names) {
                assertFalse(name, File(folder, name).exists())
                val kept =
                    File(folder, "backup").listFiles().orEmpty().filter {
                        it.name.startsWith(
                            name.substringBefore('.') + "-",
                        )
                    }
                assertEquals(listOf("$name#\n"), kept.map { it.readText() })
            }
        }

    @Test
    fun aNewCompetitionWhoseFilesCannotMoveChangesNothing() =
        runTest {
            configure(sendEnabled = false)
            repository.record("1", CutoffEvent.NEXT_LAP)
            File(folder, "start.txt").writeText("15#\n")
            File(folder, "backup").deleteRecursively()
            File(folder, "backup").writeText("not a folder")
            var cleared = false

            repository.whenANewCompetitionStarts { cleared = true }
            assertFalse(repository.startNewCompetition())

            assertFalse(cleared)
            assertEquals(1, db.cutoffDao().getAll().size)
            assertTrue(File(folder, "start.txt").exists())
            assertTrue(File(folder, "results.txt").readText().contains("1#"))
            assertTrue(repository.backupFailed.value)
        }

    @Test
    fun aNewCompetitionWhoseResultsCannotBeResetPutsItsFilesBack() =
        runTest {
            configure(sendEnabled = false)
            folder.mkdirs()
            File(folder, "start.txt").writeText("15#\n")
            File(folder, "chip_reads.txt").writeText("A1#\n")
            // A folder by that name cannot be replaced by the emptied results.txt.
            File(folder, "results.txt").apply { mkdirs() }.resolve("in-the-way").writeText("x")

            assertFalse(repository.startNewCompetition())

            assertEquals("15#\n", File(folder, "start.txt").readText())
            assertEquals("A1#\n", File(folder, "chip_reads.txt").readText())
            assertTrue(File(folder, "backup").listFiles().orEmpty().isEmpty())
        }

    @Test
    fun startNewCompetitionKeepsStateWhenResultsResetFails() =
        runTest {
            val blocker =
                File(
                    RuntimeEnvironment.getApplication().cacheDir,
                    "new-competition-blocker-${System.nanoTime()}",
                )
            blocker.writeText("x")
            configure(sendEnabled = false, folderPath = File(blocker, "sub").path)
            store.save(store.load().copy(token = "tok", pointNumber = 5))
            db
                .cutoffDao()
                .insert(
                    CutoffEntity(
                        number = "11",
                        timeStr = "t",
                        event = CutoffEvent.NEXT_LAP,
                        createdAt = 1,
                    ),
                )

            assertFalse(repository.startNewCompetition())

            val all = db.cutoffDao().getAll()
            assertEquals(1, all.size)
            assertEquals("11", all[0].number)
            assertEquals("tok", store.load().token)
            assertEquals(5, store.load().pointNumber)
            assertTrue(repository.backupFailed.value)
        }
}
