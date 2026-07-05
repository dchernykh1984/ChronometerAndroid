package com.dchernykh.chronometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChronometerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ChronometerApp
    private val repository =
        CutoffRepository(app, app.database.cutoffDao(), app.settingsStore, app.backupWriter)

    val cutoffs: StateFlow<List<CutoffEntity>> =
        repository.cutoffs.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList(),
        )

    /** Diagnostics (do not affect recording): last backup outcome + upload state. */
    val backupFailed: StateFlow<Boolean> = repository.backupFailed

    val uploadStatus: StateFlow<UploadStatus> =
        repository.uploadWorkInfo
            .map { infos -> uploadStatusOf(infos.map { it.state }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UploadStatus.IDLE)

    fun recordCutoff(number: String) {
        viewModelScope.launch { repository.record(number, CutoffEvent.NEXT_LAP) }
    }

    fun recordDisqualification(number: String) {
        viewModelScope.launch { repository.record(number, CutoffEvent.DSQ) }
    }

    fun loadSettings(): Settings = app.settingsStore.load()

    fun saveSettings(settings: Settings) = app.settingsStore.save(settings)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
