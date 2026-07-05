package com.dchernykh.chronometer.data

/** User-facing configuration needed to record and push cutoffs. */
data class Settings(
    val siteUrl: String,
    val token: String,
    val pointNumber: Int,
    val deviceUuid: String,
    val folderPath: String,
    val sendEnabled: Boolean,
)

private val ALLOWED_UPLOAD_SCHEMES = setOf("http", "https")

/**
 * True when [url] uses an allowed scheme (http/https). Anything else - ftp://,
 * file://, or a scheme-less string - is rejected so the upload never tries to
 * open a non-HTTP connection.
 */
fun hasAllowedUploadScheme(url: String): Boolean =
    url.substringBefore("://", missingDelimiterValue = "").lowercase() in ALLOWED_UPLOAD_SCHEMES

/** True when the upload target is fully configured (valid URL, token, device id, point). */
val Settings.isUploadConfigured: Boolean
    get() = hasAllowedUploadScheme(siteUrl) && token.isNotBlank() && deviceUuid.isNotBlank() && pointNumber >= 0

/** True when uploads should actually be attempted (configured and turned on). */
val Settings.isUploadReady: Boolean
    get() = sendEnabled && isUploadConfigured
