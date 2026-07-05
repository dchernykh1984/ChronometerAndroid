package com.dchernykh.chronometer

import android.app.Application
import androidx.room.Room
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.net.UploadClient

/** Holds the app-wide singletons (manual dependency injection). */
class ChronometerApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settingsStore: SettingsStore
        private set

    val uploadClient = UploadClient()
    val backupWriter = BackupWriter()

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "chronometer.db").build()
        settingsStore = SettingsStore(this)
    }
}
