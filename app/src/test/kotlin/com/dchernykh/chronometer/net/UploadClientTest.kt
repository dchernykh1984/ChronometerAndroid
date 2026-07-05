package com.dchernykh.chronometer.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertEquals(UploadResult.SUCCESS, result)
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
            assertEquals(UploadResult.RETRY, UploadClient().upload(baseUrl, "t", "d", 0, listOf("x"), 1))
        }
    }

    @Test
    fun uploadGivesUpOn4xx() {
        withServer(responseCode = 400) { _, baseUrl ->
            assertEquals(UploadResult.GIVE_UP, UploadClient().upload(baseUrl, "t", "d", 0, listOf("x"), 1))
        }
    }

    @Test
    fun uploadRetriesOnConnectionError() {
        // Nothing is listening on port 1, so the connection is refused -> retry.
        assertEquals(UploadResult.RETRY, UploadClient().upload("http://127.0.0.1:1", "t", "d", 0, listOf("x"), 1))
    }

    @Test
    fun uploadGivesUpOnMalformedUrl() {
        assertEquals(UploadResult.GIVE_UP, UploadClient().upload("not-a-url", "t", "d", 0, listOf("x"), 1))
    }

    @Test
    fun uploadGivesUpOnDisallowedScheme() {
        assertEquals(UploadResult.GIVE_UP, UploadClient().upload("ftp://host", "t", "d", 0, listOf("x"), 1))
        assertEquals(UploadResult.GIVE_UP, UploadClient().upload("file:///tmp/x", "t", "d", 0, listOf("x"), 1))
    }

    private fun withServer(
        responseCode: Int,
        block: (server: MockWebServer, baseUrl: String) -> Unit,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(responseCode))
        server.start()
        try {
            block(server, server.url("/").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }
}
