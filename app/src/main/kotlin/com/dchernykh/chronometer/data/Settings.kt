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
