package com.dchernykh.chronometer

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import com.dchernykh.chronometer.beam.BeamRepository
import com.dchernykh.chronometer.chips.ChipMap
import com.dchernykh.chronometer.chips.ChipRepository
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.MIGRATION_1_2
import com.dchernykh.chronometer.data.MIGRATION_2_3
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.UploadErrorStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.net.UploadClient
import com.dchernykh.chronometer.source.SourceController
import com.dchernykh.chronometer.source.SourceEvent

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

    val cutoffRepository by lazy { CutoffRepository(this, database, settingsStore, backupWriter) }

    val chipRepository by lazy { ChipRepository(database, settingsStore, backupWriter, cutoffRepository) }

    val beamRepository by lazy { BeamRepository(database, settingsStore, backupWriter, cutoffRepository) }

    /**
     * The cutoff source timing mode runs: beam breaks go to the queue that waits for numbers,
     * chip reads to their passes.
     */
    val sourceController by lazy {
        SourceController(
            settingsStore,
            SystemClock::elapsedRealtime,
            chipMap = { chipRepository.protocol.value?.map ?: ChipMap() },
        ) { batch ->
            beamRepository.add(batch.filterIsInstance<SourceEvent.BeamBreak>())
            chipRepository.apply(batch.filterIsInstance<SourceEvent.ChipRead>())
        }
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
