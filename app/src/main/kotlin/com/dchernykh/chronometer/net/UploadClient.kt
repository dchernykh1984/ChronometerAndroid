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
    ): UploadResult {
        if (!hasAllowedUploadScheme(siteUrl)) {
            // ftp://, file://, or scheme-less: never openable as HTTP - give up.
            return UploadResult.GIVE_UP
        }
        return post(
            endpointFor(siteUrl, pointNumber),
            buildPayload(token, deviceId, pointNumber, items, clientRevision),
        )
    }

    private fun post(
        endpoint: String,
        body: String,
    ): UploadResult =
        try {
            val connection = open(endpoint)
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                resultForStatus(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        } catch (_: MalformedURLException) {
            // Misconfigured site URL - do not retry forever.
            UploadResult.GIVE_UP
        } catch (_: IOException) {
            // Connectivity problem - retry when the network returns.
            UploadResult.RETRY
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
