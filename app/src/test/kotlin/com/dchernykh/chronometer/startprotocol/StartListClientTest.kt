package com.dchernykh.chronometer.startprotocol

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartListClientTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun problemOf(block: () -> Unit): StartListException {
        try {
            block()
        } catch (e: StartListException) {
            return e
        }
        fail("no problem")
        error("unreachable")
    }

    private fun fetch(token: String = "tok") = StartListClient().fetch(server.url("/").toString(), token)

    @Test
    fun everyDevicesLinesComeInTheSitesOrderWithoutBlankOnes() {
        server.enqueue(
            MockResponse().setBody(
                """{"devices": [{"device_id": "a", "items": ["15#A#", "  ", "16#B#"]}, {"items": "x"}, 7,""" +
                    """ {"items": ["17#C#"]}]}""",
            ),
        )
        assertEquals(listOf("15#A#", "16#B#", "17#C#"), fetch(token = "a b&c"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/start-list/?competition_token=a+b%26c", request.path)
    }

    @Test
    fun anHttpErrorSaysItsStatus() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such competition"))
        val problem = problemOf { fetch() }
        assertEquals(DownloadProblem.HTTP, problem.problem)
        assertTrue(problem.detail, problem.detail.startsWith("HTTP 404"))
    }

    @Test
    fun noConnectionSaysWhyAndNeverTheToken() {
        val problem = problemOf { StartListClient().fetch("http://127.0.0.1:1", "secret-token") }
        assertEquals(DownloadProblem.NETWORK, problem.problem)
        assertTrue(problem.detail, problem.detail.contains("Exception"))
        assertFalse(problem.detail.contains("secret-token"))
    }

    @Test
    fun anythingButLinesOfTextIsInvalid() {
        val bodies =
            listOf(
                "not json",
                "[1]",
                """{"devices": {}}""",
                """{"devices": null}""",
                """{"devices": [{"items": [7]}]}""",
                """{"devices": [{"items": ["15#A#\u2028"]}]}""",
                """{"devices": [{"items": ["15#A#\r"]}]}""",
                """{"devices": [{"items": ["15#A#"]}]} and more""",
            )
        for (body in bodies) {
            assertEquals(body, DownloadProblem.INVALID, problemOf { StartListClient.linesOf(body) }.problem)
        }
    }

    @Test
    fun anAnswerOfNoLinesIsNoStartListYet() {
        for (body in listOf("{}", """{"devices": []}""", """{"devices": [{"items": ["", " "]}]}""")) {
            assertEquals(body, DownloadProblem.EMPTY, problemOf { StartListClient.linesOf(body) }.problem)
        }
    }

    @Test
    fun anAnswerNotInUtf8IsInvalid() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(byteArrayOf('{'.code.toByte(), 0xFF.toByte(), '}'.code.toByte()))),
        )
        assertEquals(DownloadProblem.INVALID, problemOf { fetch() }.problem)
    }
}
