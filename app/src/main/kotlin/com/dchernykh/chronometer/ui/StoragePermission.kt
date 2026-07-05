package com.dchernykh.chronometer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * All-files access so the app can write into the configured folder in shared
 * storage (`/sdcard/android_chronometer` by default). API 30+ uses
 * MANAGE_EXTERNAL_STORAGE (granted from a system screen); API 24-29 uses the
 * runtime WRITE_EXTERNAL_STORAGE permission.
 *
 * All-files access (over SAF) is a deliberate product decision so the timing
 * files live at a plain, predictable path that referees can inspect with a file
 * manager and that the desktop tools consume unchanged. See
 * `docs/storage-access-decision.md`. A missing or revoked grant is detected by
 * [isGranted] (surfaced as a "grant" button in Settings) and, at the write
 * layer, by BackupWriter reporting failure without ever blocking a cutoff.
 */
object StoragePermission {
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun manageAllFilesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
