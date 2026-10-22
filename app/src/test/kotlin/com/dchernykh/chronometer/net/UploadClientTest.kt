package com.dchernykh.chronometer.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadClientTest {
    @Test
    fun endpointForPointZeroIsFinishTimes() {
        assertEquals("http://host/api/v1/finish-times/", UploadClient.endpointFor("http://host/", 0))
    }

    @Test
    fun endpointForRemotePoint() {
        assertEquals("http://host/api/v1/remote-points/", UploadClient.endpointFor("http://host", 3))
    }

    @Test
    fun statusMapping() {
        assertEquals(UploadResult.SUCCESS, UploadClient.resultForStatus(200))
        assertEquals(UploadResult.SUCCESS, UploadClient.resultForStatus(204))
        assertEquals(UploadResult.RETRY, UploadClient.resultForStatus(503))
        assertEquals(UploadResult.GIVE_UP, UploadClient.resultForStatus(400))
        assertEquals(UploadResult.GIVE_UP, UploadClient.resultForStatus(409))
    }

    @Test
    fun payloadForRemotePointHasAllFields() {
        val json = UploadClient.buildPayload("tok", "dev", 2, listOf("1#t#nextLap#"), 5)
        assertTrue(json.contains("\"competition_token\":\"tok\""))
        assertTrue(json.contains("\"device_id\":\"dev\""))
        assertTrue(json.contains("\"items\":[\"1#t#nextLap#\"]"))
        assertTrue(json.contains("\"client_revision\":5"))
        assertTrue(json.contains("\"point_number\":2"))
    }

    @Test
    fun payloadCarriesARevisionBeyondTheIntRange() {
        val json = UploadClient.buildPayload("tok", "dev", 0, emptyList(), 3_000_000_000L)
        assertTrue(json.contains("\"client_revision\":3000000000"))
    }

    @Test
    fun payloadForFinishOmitsPointNumber() {
        val json = UploadClient.buildPayload("tok", "dev", 0, emptyList(), 1)
        assertFalse(json.contains("point_number"))
    }

    @Test
    fun payloadEscapesQuotes() {
        val json = UploadClient.buildPayload("a\"b", "dev", 0, emptyList(), 1)
        assertTrue(json.contains("\"competition_token\":\"a\\\"b\""))
    }

    @Test
    fun uploadPostsToFinishTimesAndSucceedsOn2xx() {
        withServer(responseCode = 204) { server, baseUrl ->
            val result = UploadClient().upload(baseUrl, "tok", "dev", 0, listOf("1#t#nextLap#"), 7)
            assertEquals(UploadResult.SUCCESS, result.result)
            assertNull(result.error)
            val request = server.takeRequest()
            assertEquals("/api/v1/finish-times/", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"device_id\":\"dev\""))
            assertTrue(body.contains("\"client_revision\":7"))
        }
    }

    @Test
    fun uploadRetriesOn5xx() {
        withServer(responseCode = 500) { _, baseUrl ->
            val outcome = UploadClient().upload(baseUrl, "race-secret", "d", 0, listOf("x"), 1)
            assertEquals(UploadResult.RETRY, outcome.result)
            assertEquals("HTTP 500 Server Error", outcome.error)
        }
    }

    @Test
    fun uploadGivesUpOn4xx() {
        withServer(responseCode = 400) { _, baseUrl ->
            val outcome = UploadClient().upload(baseUrl, "race-secret", "d", 0, listOf("x"), 1)
            assertEquals(UploadResult.GIVE_UP, outcome.result)
            assertEquals("HTTP 400 Client Error", outcome.error)
        }
    }

    @Test
    fun uploadRetriesOnConnectionError() {
        // Nothing is listening on port 1, so the connection is refused -> retry.
        val outcome = UploadClient().upload("http://127.0.0.1:1", "race-secret", "d", 0, listOf("x"), 1)
        assertEquals(UploadResult.RETRY, outcome.result)
        assertTrue(outcome.error!!.contains("ConnectException"))
        assertTrue(outcome.error.substringAfter(':').isNotBlank())
    }

    @Test
    fun uploadGivesUpOnMalformedUrl() {
        val outcome = UploadClient().upload("not-a-url", "race-secret", "d", 0, listOf("x"), 1)
        assertEquals(UploadResult.GIVE_UP, outcome.result)
        assertTrue(outcome.error!!.contains("MalformedURLException"))
    }

    @Test
    fun uploadGivesUpOnDisallowedScheme() {
        for (url in listOf("ftp://host", "file:///tmp/x", "http://host:bad")) {
            val outcome = UploadClient().upload(url, "race-secret", "d", 0, listOf("x"), 1)
            assertEquals(UploadResult.GIVE_UP, outcome.result)
            assertTrue(outcome.error!!.contains("MalformedURLException"))
        }
    }

    @Test
    fun uploadExplainsAnOutOfRangePort() {
        val outcome = UploadClient().upload("http://127.0.0.1:65536", "race-secret", "d", 0, emptyList(), 1)
        assertEquals(UploadResult.GIVE_UP, outcome.result)
        assertTrue(outcome.error!!.contains("IllegalArgumentException"))
        assertTrue(outcome.error.contains("65536"))
    }

    @Test
    fun serverReasonIsBoundedAndDoesNotExposeTheToken() {
        val token = "secret-\"quoted\""
        val body = "{\"detail\":\"Invalid token secret-\\\"quoted\\\"\"}\nraw=$token\n" + "x".repeat(10_000)
        withServer(responseCode = 403, responseBody = body) { _, baseUrl ->
            val outcome = UploadClient().upload(baseUrl, token, "d", 0, listOf("x"), 1)
            assertEquals(UploadResult.GIVE_UP, outcome.result)
            val error = outcome.error!!
            assertTrue(error.startsWith("HTTP 403"))
            assertTrue(error.contains("Invalid token [redacted]"))
            assertTrue(error.contains("raw=[redacted]"))
            assertFalse(error.contains("secret-"))
            assertFalse(error.contains('\n'))
            assertEquals(300, error.length)
        }
    }

    @Test
    fun serverReasonWithAnEmptyTokenIsStillReadable() {
        withServer(responseCode = 400, responseBody = "Unknown competition") { _, baseUrl ->
            val outcome = UploadClient().upload(baseUrl, "", "d", 0, emptyList(), 1)
            assertTrue(outcome.error!!.contains("Unknown competition"))
        }
    }

    private fun withServer(
        responseCode: Int,
        responseBody: String = "",
        block: (server: MockWebServer, baseUrl: String) -> Unit,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(responseCode).setBody(responseBody))
        server.start()
        try {
            block(server, server.url("/").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }
}
