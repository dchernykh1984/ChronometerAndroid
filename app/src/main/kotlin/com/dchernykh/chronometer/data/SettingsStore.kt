package com.dchernykh.chronometer.data

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import java.io.File
import java.util.UUID

/**
 * Persists [Settings] plus the internal `client_revision` counter in
 * SharedPreferences. A device UUID is generated on first use if none is set.
 *
 * SECURITY TECH DEBT: the upload [Settings.token] is stored in plain
 * SharedPreferences. That storage is app-private and Auto Backup is disabled
 * (see AndroidManifest `allowBackup=false`), but the token should be moved to
 * Android Keystore-backed storage before wider distribution.
 */
class SettingsStore(
    context: Context,
) {
    // applicationContext is null while an Activity is still in attachBaseContext
    // (where we read the language), so fall back to the given context; both map to
    // the same per-app SharedPreferences file.
    private val prefs =
        (context.applicationContext ?: context)
            .getSharedPreferences("chronometer_settings", Context.MODE_PRIVATE)

    /** Default local folder: `android_chronometer` in the phone's shared storage root. */
    fun defaultFolderPath(): String =
        File(Environment.getExternalStorageDirectory(), "android_chronometer").absolutePath

    fun load(): Settings {
        ensureDeviceUuid()
        return Settings(
            siteUrl = prefs.getString(KEY_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            pointNumber = prefs.getInt(KEY_POINT, 0),
            deviceUuid = prefs.getString(KEY_UUID, "").orEmpty(),
            folderPath = prefs.getString(KEY_FOLDER, null) ?: defaultFolderPath(),
            sendEnabled = prefs.getBoolean(KEY_SEND, false),
            numericInput = prefs.getBoolean(KEY_NUMERIC, true),
            finishMode = prefs.getBoolean(KEY_FINISH, false),
            themeMode = readThemeMode(),
            language = AppLanguage.fromName(prefs.getString(KEY_LANG, null)),
        )
    }

    fun save(settings: Settings) {
        prefs.edit {
            putString(KEY_URL, settings.siteUrl.trim())
            putString(KEY_TOKEN, settings.token.trim())
            putInt(KEY_POINT, settings.pointNumber)
            putString(KEY_UUID, settings.deviceUuid.ifBlank { UUID.randomUUID().toString() })
            putString(KEY_FOLDER, settings.folderPath.ifBlank { defaultFolderPath() })
            putBoolean(KEY_SEND, settings.sendEnabled)
            putBoolean(KEY_NUMERIC, settings.numericInput)
            putBoolean(KEY_FINISH, settings.finishMode)
            putString(KEY_THEME, settings.themeMode.name)
            putString(KEY_LANG, settings.language.name)
        }
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    /**
     * Start a new competition: clear the upload token, reset the control point to
     * 0 and the revision counter. Folder, device id and preferences are kept; the
     * on-disk backups are not touched here (the caller only resets `results.txt`).
     */
    fun resetForNewCompetition() {
        prefs.edit {
            putString(KEY_TOKEN, "")
            putInt(KEY_POINT, 0)
            putInt(KEY_REV, 0)
        }
    }

    /** Current highest revision (the server rejects a stale one with HTTP 409). */
    fun clientRevision(): Int = prefs.getInt(KEY_REV, 0)

    /** Bump the revision on each cutoff so the newest snapshot is always accepted. */
    fun nextClientRevision(): Int {
        val next = clientRevision() + 1
        prefs.edit { putInt(KEY_REV, next) }
        return next
    }

    private fun ensureDeviceUuid() {
        if (prefs.getString(KEY_UUID, "").isNullOrBlank()) {
            prefs.edit { putString(KEY_UUID, UUID.randomUUID().toString()) }
        }
    }

    private companion object {
        const val KEY_URL = "site_url"
        const val KEY_TOKEN = "token"
        const val KEY_POINT = "point_number"
        const val KEY_UUID = "device_uuid"
        const val KEY_FOLDER = "folder_path"
        const val KEY_SEND = "send_enabled"
        const val KEY_NUMERIC = "numeric_input"
        const val KEY_FINISH = "finish_mode"
        const val KEY_THEME = "theme_mode"
        const val KEY_LANG = "language"
        const val KEY_REV = "client_revision"
    }
}
