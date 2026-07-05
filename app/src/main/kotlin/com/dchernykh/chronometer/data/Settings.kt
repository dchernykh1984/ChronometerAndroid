package com.dchernykh.chronometer.data

/** UI language; SYSTEM follows the device locale, others force that language. */
enum class AppLanguage(
    val tag: String,
) {
    SYSTEM(""),
    RU("ru"),
    EN("en"),
    KK("kk"),
    ;

    companion object {
        /** Parse a stored enum name back to a language, defaulting to SYSTEM. */
        fun fromName(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Which color scheme the app uses; SYSTEM follows the device setting. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Resolve to a dark scheme, consulting the system flag only in SYSTEM mode. */
    fun resolvesToDark(systemInDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemInDark
            LIGHT -> false
            DARK -> true
        }
}

/** User-facing configuration needed to record and push cutoffs. */
data class Settings(
    val siteUrl: String,
    val token: String,
    val pointNumber: Int,
    val deviceUuid: String,
    val folderPath: String,
    val sendEnabled: Boolean,
    /** Number field accepts digits only (true) or any text (false). */
    val numericInput: Boolean = true,
    /** Record `finish` instead of `nextLap` for a regular cutoff press. */
    val finishMode: Boolean = false,
    /** Color scheme selection (default follows the system). */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** UI language selection (default follows the system). */
    val language: AppLanguage = AppLanguage.SYSTEM,
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
