package com.dchernykh.chronometer.work

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.ui.UploadStatus
import com.dchernykh.chronometer.ui.uploadStatusOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
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
    fun permanentHttpErrorSurfacesAsFailedStatus() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            configureUpload(pointNumber = 0)
            repository.record("11", CutoffEvent.NEXT_LAP)

            assertEquals(ListenableWorker.Result.failure(), runWorker())
            // A failed worker maps to the on-screen "Upload: error" status.
            assertEquals(UploadStatus.FAILED, uploadStatusOf(listOf(WorkInfo.State.FAILED)))
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
        }
}
