package com.dchernykh.chronometer.startprotocol

import com.dchernykh.chronometer.chips.ChipMap
import com.dchernykh.chronometer.io.RawFiles
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

// The upload client's timeouts.
private const val TIMEOUT_MS = 10_000

/** Why a download changed nothing; the screen says it in the referee's language. */
enum class DownloadProblem {
    NO_SITE,
    NETWORK,
    HTTP,

    /** Not JSON at all; the detail is the parser's own words. */
    INVALID,

    /** JSON, or not even text, but no object with a list of devices. */
    NOT_A_START_LIST,

    /** A line that is not one line of text. */
    BAD_LINE,
    EMPTY,
    FILE,
}

/** A start list download, for its line in the settings (spec FR-009). */
sealed interface Download {
    data object Running : Download

    data class Done(
        val lines: Int,
        val map: ChipMap,
    ) : Download

    /** Nothing changed; [detail] is the system's own words, shown as they are. */
    data class Failed(
        val problem: DownloadProblem,
        val detail: String = "",
    ) : Download
}

class StartListException(
    val problem: DownloadProblem,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception("$problem $detail", cause)

/**
 * `GET <site URL>/api/v1/start-list/?competition_token=<token>`, the request
 * FinishProtocolGenerator makes, read as ChronometerPython's `fetch_start_list`: the lines
 * of every device in the order the site returns them, blank ones left out.
 */
class StartListClient {
    /** The start list's lines; throws [StartListException] when there are none to use. */
    fun fetch(
        siteUrl: String,
        token: String,
    ): List<String> {
        val encoded = URLEncoder.encode(token, "UTF-8")
        val url = "${siteUrl.trimEnd('/')}/api/v1/start-list/?competition_token=$encoded"
        val body =
            try {
                get(url)
            } catch (e: IOException) {
                throw StartListException(
                    DownloadProblem.NETWORK,
                    redacted("${e.javaClass.simpleName}: ${e.message}", token),
                    e,
                )
            } catch (e: IllegalArgumentException) {
                // URL takes some invalid ports the connection then refuses.
                throw StartListException(
                    DownloadProblem.NETWORK,
                    redacted("${e.javaClass.simpleName}: ${e.message}", token),
                    e,
                )
            }
        return linesOf(body)
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
                throw StartListException(
                    DownloadProblem.HTTP,
                    "HTTP $code ${connection.responseMessage.orEmpty()}".trim(),
                )
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            return try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                notAStartList()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun redacted(
        message: String,
        token: String,
    ) = if (token.isEmpty()) message else message.replace(token, "[redacted]")

    internal companion object {
        /**
         * The lines of an answer: an object whose `devices` list holds objects whose `items`
         * are the lines. Anything but one line of text is refused, so junk never replaces a
         * good start.txt; no line at all is a failure, so a press before the upload neither.
         */
        fun linesOf(body: String): List<String> {
            // The parser may throw at the end too (an unterminated comment after the object).
            val devices =
                try {
                    val tokener = JSONTokener(body)
                    (tokener.nextValue() as? JSONObject)?.takeIf { tokener.nextClean() == 0.toChar() }?.let {
                        it.opt("devices") ?: JSONArray()
                    }
                } catch (e: JSONException) {
                    throw StartListException(DownloadProblem.INVALID, e.message.orEmpty(), e)
                }
            if (devices !is JSONArray) {
                notAStartList()
            }
            val lines = (0 until devices.length()).flatMap { itemsOf(devices.opt(it)) }
            if (lines.isEmpty()) {
                throw StartListException(DownloadProblem.EMPTY)
            }
            return lines
        }

        private fun itemsOf(device: Any?): List<String> {
            val items = (device as? JSONObject)?.opt("items") as? JSONArray ?: return emptyList()
            return (0 until items.length())
                .map { items.opt(it) }
                .map { line ->
                    (line as? String)?.takeUnless { RawFiles.LINE_BREAKS.containsMatchIn(it) }
                        ?: throw StartListException(DownloadProblem.BAD_LINE)
                }.filter(String::isNotBlank)
        }

        private fun notAStartList(): Nothing = throw StartListException(DownloadProblem.NOT_A_START_LIST)
    }
}
