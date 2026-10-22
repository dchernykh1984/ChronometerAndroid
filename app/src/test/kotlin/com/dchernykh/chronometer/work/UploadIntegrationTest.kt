package com.dchernykh.chronometer.work

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.UploadErrorStore
import com.dchernykh.chronometer.ui.UploadStatus
import com.dchernykh.chronometer.ui.uploadDiagnosticsOf
import com.dchernykh.chronometer.ui.uploadStatusOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage of the "cutoff -> results.txt -> background upload of the
 * whole snapshot -> UI status" chain, wired through the real app singletons
 * (Room, SettingsStore, the HTTP client) plus a test WorkManager and a
 * MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class UploadIntegrationTest {
    private lateinit var app: ChronometerApp
    private lateinit var repository: CutoffRepository
    private lateinit var server: MockWebServer
    private lateinit var folder: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as ChronometerApp
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking { app.database.cutoffDao().deleteAll() }
        server = MockWebServer()
        server.start()
        folder = File(app.cacheDir, "upload-${System.nanoTime()}")
        repository = CutoffRepository(app, app.database.cutoffDao(), app.settingsStore, app.backupWriter)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun configureUpload(pointNumber: Int) {
        app.settingsStore.save(
            app.settingsStore.load().copy(
                siteUrl = server.url("/").toString().trimEnd('/'),
                token = "race-token",
                pointNumber = pointNumber,
                folderPath = folder.path,
                sendEnabled = true,
            ),
        )
    }

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<UploadWorker>(app).build().doWork() }

    private fun uploadStatus(): UploadStatus =
        uploadStatusOf(
            WorkManager
                .getInstance(app)
                .getWorkInfosForUniqueWork("chronometer_upload")
                .get()
                .map { it.state },
        )

    @Test
    fun recordsWriteTheFullResultsFileAndQueueTheUpload() =
        runTest {
            configureUpload(pointNumber = 3)
            repository.record("11", CutoffEvent.NEXT_LAP)
            repository.record("22", CutoffEvent.NEXT_LAP)

            val results = File(folder, "results.txt").readText()
            assertTrue(results.contains("11#"))
            assertTrue(results.contains("22#"))
            assertEquals(2, results.trim().lines().size)

            // Sending is on but the network constraint is unmet: queued, not failed.
            assertEquals(UploadStatus.PENDING, uploadStatus())
        }

    @Test
    fun workerUploadsTheWholeSnapshotWithEveryPayloadField() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            configureUpload(pointNumber = 3)
            repository.record("11", CutoffEvent.NEXT_LAP)
            repository.record("22", CutoffEvent.NEXT_LAP)

            assertEquals(ListenableWorker.Result.success(), runWorker())

            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("/api/v1/remote-points/", request!!.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"competition_token\":\"race-token\""))
            assertTrue(body.contains("\"device_id\":\"${app.settingsStore.load().deviceUuid}\""))
            assertTrue(body.contains("\"point_number\":3"))
            assertTrue(body.contains("\"client_revision\":"))
            assertTrue(body.contains("11#"))
            assertTrue(body.contains("22#"))
        }

    @Test
    fun workerUploadsAnEmptySnapshotWithANewerRevisionAfterDeletingTheLastCutoff() =
        runTest {
            // Both endpoints replace this device's full stream, including an empty list.
            for (point in listOf(0, 3)) {
                configureUpload(pointNumber = point)
                repository.record("11", CutoffEvent.NEXT_LAP)
                val revision = app.settingsStore.clientRevision()
                val row =
                    app.database
                        .cutoffDao()
                        .getAll()
                        .single()
                assertTrue(repository.deleteCutoff(row.id))
                server.enqueue(MockResponse().setResponseCode(204))

                assertEquals(ListenableWorker.Result.success(), runWorker())

                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                val endpoint = if (point == 0) "finish-times" else "remote-points"
                assertEquals("/api/v1/$endpoint/", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("\"items\":[]"))
                assertTrue(body.contains("\"client_revision\":${revision + 1}"))
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun workerWaitsForTheMutationAndReadsTheMatchingListAndRevision() =
        runTest {
            configureUpload(pointNumber = 0)
            repository.record("11", CutoffEvent.NEXT_LAP)
            server.enqueue(MockResponse().setResponseCode(204))
            val worker = TestListenableWorkerBuilder<UploadWorker>(app).build()
            val upload: Deferred<ListenableWorker.Result>
            val revision: Long
            CutoffRepository.mutationMutex.withLock {
                upload = async { worker.doWork() }
                runCurrent()
                assertFalse(upload.isCompleted)
                assertEquals(0, server.requestCount)
                app.database.cutoffDao().deleteAll()
                app.settingsStore.allowEmptySnapshot()
                revision = app.settingsStore.nextClientRevision()
            }
            assertEquals(ListenableWorker.Result.success(), upload.await())
            val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(body.contains("\"items\":[]"))
            assertTrue(body.contains("\"client_revision\":$revision"))
        }

    @Test
    fun workerDoesNotClearTheServerForAFreshEmptyList() =
        runTest {
            configureUpload(pointNumber = 0)
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun permanentHttpErrorSurfacesAsFailedStatus() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("Unknown competition token"))
            configureUpload(pointNumber = 0)
            repository.record("11", CutoffEvent.NEXT_LAP)

            assertEquals(ListenableWorker.Result.failure(), runWorker())
            // A failed worker maps to the on-screen "Upload: error" status.
            assertEquals(UploadStatus.FAILED, uploadStatusOf(listOf(WorkInfo.State.FAILED)))
            val error = app.uploadErrorStore.error.value!!
            val info = WorkInfo(UUID.fromString(error.workId), WorkInfo.State.FAILED, emptySet())
            assertTrue(uploadDiagnosticsOf(listOf(info), error).error!!.contains("HTTP 400"))
            assertTrue(error.message.contains("Unknown competition token"))
        }

    @Test
    fun cancelledUploadCannotReplaceTheCurrentDiagnostic() =
        runTest {
            val releaseResponse = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        releaseResponse.await(5, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(400).setBody("Old failure")
                    }
                }
            configureUpload(pointNumber = 0)
            repository.record("11", CutoffEvent.NEXT_LAP)
            app.uploadErrorStore.record("current-work", "HTTP 403: Current failure")
            val currentError = app.uploadErrorStore.error.value
            val worker = TestListenableWorkerBuilder<UploadWorker>(app).build()
            val attempt = launch(Dispatchers.IO) { worker.doWork() }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                attempt.cancel()
                releaseResponse.countDown()
                attempt.join()
                assertTrue(attempt.isCancelled)
                assertEquals(currentError, app.uploadErrorStore.error.value)
            } finally {
                releaseResponse.countDown()
                attempt.cancelAndJoin()
            }
        }

    @Test
    fun transientHttpErrorRetriesAndStaysQueued() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            configureUpload(pointNumber = 0)
            repository.record("11", CutoffEvent.NEXT_LAP)

            assertEquals(ListenableWorker.Result.retry(), runWorker())
            // A retry goes back to the queue, shown as "Upload: queued".
            assertEquals(UploadStatus.PENDING, uploadStatusOf(listOf(WorkInfo.State.ENQUEUED)))
            // WorkManager clears progress when retrying, so the reason is durable.
            val error = UploadErrorStore(app).error.value!!
            val info = WorkInfo(UUID.fromString(error.workId), WorkInfo.State.ENQUEUED, emptySet())
            assertTrue(uploadDiagnosticsOf(listOf(info), error).error!!.contains("HTTP 500"))
        }
}
