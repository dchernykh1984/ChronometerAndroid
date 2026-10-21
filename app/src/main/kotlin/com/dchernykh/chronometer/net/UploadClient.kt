package com.dchernykh.chronometer.net

import com.dchernykh.chronometer.data.hasAllowedUploadScheme
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** Outcome of an upload attempt, used to choose the WorkManager result. */
enum class UploadResult {
    /** Delivered (HTTP 2xx). */
    SUCCESS,

    /** Transient failure (network error or HTTP 5xx) - worth retrying. */
    RETRY,

    /** Permanent failure (bad URL, HTTP 4xx) - retrying will not help. */
    GIVE_UP,
}

data class UploadOutcome(
    val result: UploadResult,
    val error: String? = null,
)

/**
 * Pushes the whole snapshot of items to the cycling-site API, mirroring
 * WindowsChronometerPython's http_io: point 0 -> /api/v1/finish-times/,
 * point N>=1 -> /api/v1/remote-points/. The competition token travels in the
 * JSON body (as in the Python client), not as a Bearer header. The JSON is built
 * by hand so upload logic is testable on the plain JVM.
 */
class UploadClient {
    fun upload(
        siteUrl: String,
        token: String,
        deviceId: String,
        pointNumber: Int,
        items: List<String>,
        clientRevision: Int,
    ): UploadOutcome {
        val outcome =
            if (!hasAllowedUploadScheme(siteUrl)) {
                UploadOutcome(UploadResult.GIVE_UP, "MalformedURLException: $siteUrl")
            } else {
                post(
                    endpointFor(siteUrl, pointNumber),
                    buildPayload(token, deviceId, pointNumber, items, clientRevision),
                )
            }
        return outcome.copy(error = outcome.error?.let { diagnostic(it, token) })
    }

    private fun post(
        endpoint: String,
        body: String,
    ): UploadOutcome =
        try {
            val connection = open(endpoint)
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                responseOutcome(connection)
            } finally {
                connection.disconnect()
            }
        } catch (error: MalformedURLException) {
            // Misconfigured site URL - do not retry forever.
            UploadOutcome(UploadResult.GIVE_UP, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            // URL accepts some invalid ports that the connection later rejects.
            UploadOutcome(UploadResult.GIVE_UP, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } catch (error: IOException) {
            // Connectivity problem - retry when the network returns.
            UploadOutcome(UploadResult.RETRY, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }

    private fun responseOutcome(connection: HttpURLConnection): UploadOutcome {
        val code = connection.responseCode
        val result = resultForStatus(code)
        if (result == UploadResult.SUCCESS) {
            return UploadOutcome(result)
        }
        val status = "HTTP $code ${connection.responseMessage.orEmpty()}".trim()
        val detail = readErrorBody(connection)
        return UploadOutcome(result, if (detail.isBlank()) status else "$status: $detail")
    }

    private fun readErrorBody(connection: HttpURLConnection): String =
        try {
            (connection.errorStream ?: connection.inputStream).bufferedReader(Charsets.UTF_8).use { reader ->
                buildString {
                    repeat(MAX_ERROR_INPUT_CHARS) {
                        val char = reader.read()
                        if (char == -1) return@buildString
                        append(char.toChar())
                    }
                }
            }
        } catch (_: IOException) {
            // Keep the HTTP status even if the server's error body cannot be read.
            ""
        }

    private fun diagnostic(
        message: String,
        token: String,
    ): String {
        val redacted =
            if (token.isEmpty()) {
                message
            } else {
                message.replace(jsonString(token).removeSurrounding("\""), "[redacted]").replace(token, "[redacted]")
            }
        return redacted.replace(WHITESPACE, " ").trim().take(MAX_ERROR_CHARS)
    }

    private fun open(endpoint: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val MAX_ERROR_INPUT_CHARS = 2_048
        private const val MAX_ERROR_CHARS = 300
        private val WHITESPACE = Regex("[\\s\\p{Cc}]+")
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_SERVER_ERR_MIN = 500
        private const val HTTP_SERVER_ERR_MAX = 599

        fun endpointFor(
            siteUrl: String,
            pointNumber: Int,
        ): String {
            val base = siteUrl.trimEnd('/')
            return if (pointNumber == 0) {
                "$base/api/v1/finish-times/"
            } else {
                "$base/api/v1/remote-points/"
            }
        }

        fun resultForStatus(code: Int): UploadResult =
            when (code) {
                in HTTP_OK_MIN..HTTP_OK_MAX -> UploadResult.SUCCESS
                in HTTP_SERVER_ERR_MIN..HTTP_SERVER_ERR_MAX -> UploadResult.RETRY
                else -> UploadResult.GIVE_UP
            }

        fun buildPayload(
            token: String,
            deviceId: String,
            pointNumber: Int,
            items: List<String>,
            clientRevision: Int,
        ): String {
            val itemsJson = items.joinToString(separator = ",", prefix = "[", postfix = "]", transform = ::jsonString)
            return buildString {
                append('{')
                append("\"competition_token\":").append(jsonString(token)).append(',')
                append("\"device_id\":").append(jsonString(deviceId)).append(',')
                append("\"items\":").append(itemsJson).append(',')
                append("\"client_revision\":").append(clientRevision)
                if (pointNumber != 0) {
                    append(",\"point_number\":").append(pointNumber)
                }
                append('}')
            }
        }

        private fun jsonString(value: String): String =
            buildString {
                append('"')
                for (c in value) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else ->
                            if (c <
                                ' '
                            ) {
                                append("\\u").append(c.code.toString(radix = 16).padStart(4, '0'))
                            } else {
                                append(c)
                            }
                    }
                }
                append('"')
            }
    }
}
