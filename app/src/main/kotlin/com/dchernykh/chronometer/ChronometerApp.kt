package com.dchernykh.chronometer

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.MIGRATION_1_2
import com.dchernykh.chronometer.data.MIGRATION_2_3
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.UploadErrorStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.net.UploadClient
import com.dchernykh.chronometer.source.SourceController

/** Holds the app-wide singletons (manual dependency injection). */
class ChronometerApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var uploadErrorStore: UploadErrorStore
        private set

    val uploadClient = UploadClient()
    val backupWriter = BackupWriter()

    /** The cutoff source timing mode runs; its events have no taker until a kind needs them. */
    val sourceController by lazy {
        SourceController(settingsStore, SystemClock::elapsedRealtime) {}
    }

    override fun onCreate() {
        super.onCreate()
        database =
            Room
                .databaseBuilder(this, AppDatabase::class.java, "chronometer.db")
                // Migrate rather than drop data: recorded cutoffs must never be lost.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        settingsStore = SettingsStore(this)
        uploadErrorStore = UploadErrorStore(this)
    }
}
