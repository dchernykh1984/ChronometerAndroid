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

/** True when the upload target is fully configured (URL, token, device id, point). */
val Settings.isUploadConfigured: Boolean
    get() = siteUrl.isNotBlank() && token.isNotBlank() && deviceUuid.isNotBlank() && pointNumber >= 0

/** True when uploads should actually be attempted (configured and turned on). */
val Settings.isUploadReady: Boolean
    get() = sendEnabled && isUploadConfigured
