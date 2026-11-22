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
import com.dchernykh.chronometer.source.board.BlePorts
import com.dchernykh.chronometer.source.board.UsbBoardPorts
import com.dchernykh.chronometer.source.builtInSource
import com.dchernykh.chronometer.time.ClockKeeper

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

    /** The phone's clock kept to world time: every cutoff is stamped on it (spec 003). */
    val clockKeeper by lazy { ClockKeeper() }

    /**
     * The cutoff source timing mode runs: beam breaks go to the queue that waits for numbers,
     * chip reads to their passes.
     */
    val sourceController by lazy {
        SourceController(
            settingsStore,
            SystemClock::elapsedRealtime,
            stamp = { system -> system?.let(clockKeeper.clock::fromSystem) ?: clockKeeper.clock.nowMs() },
            competition = { cutoffRepository.competition },
        ) { batch, takenIn, firstTry ->
            beamRepository.add(batch.filterIsInstance<SourceEvent.BeamBreak>(), takenIn, rawToo = firstTry)
            chipRepository.apply(batch.filterIsInstance<SourceEvent.ChipRead>(), takenIn, rawToo = firstTry)
        }.apply {
            // The test chips pass the riders of the map in use.
            sourceFor = {
                builtInSource(
                    it,
                    chipMap = { chipRepository.protocol.value?.map ?: ChipMap() },
                    boards = { UsbBoardPorts(this@ChronometerApp) },
                    radios = { BlePorts(this@ChronometerApp, settingsStore.load().beamBoardName) },
                )
            }
            cutoffRepository.whenANewCompetitionStarts(::startOver)
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
