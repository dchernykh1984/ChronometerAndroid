package com.dchernykh.chronometer.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes the whole snapshot of items to the cycling-site API, mirroring
 * WindowsChronometerPython's http_io: point 0 -> /api/v1/finish-times/,
 * point N>=1 -> /api/v1/remote-points/. The competition token travels in the
 * JSON body (as in the Python client), not as a Bearer header.
 */
class UploadClient {
    fun upload(
        siteUrl: String,
        token: String,
        deviceId: String,
        pointNumber: Int,
        items: List<String>,
        clientRevision: Int,
    ): Boolean {
        val base = siteUrl.trimEnd('/')
        val endpoint =
            if (pointNumber == 0) "$base/api/v1/finish-times/" else "$base/api/v1/remote-points/"
        val payload =
            JSONObject().apply {
                put("competition_token", token)
                put("device_id", deviceId)
                put("items", JSONArray(items))
                put("client_revision", clientRevision)
                if (pointNumber != 0) put("point_number", pointNumber)
            }
        return post(endpoint, payload.toString())
    }

    private fun post(
        endpoint: String,
        body: String,
    ): Boolean {
        val connection =
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in HTTP_OK_RANGE
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        val HTTP_OK_RANGE = 200..299
    }
}
