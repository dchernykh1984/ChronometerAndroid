package com.dchernykh.chronometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun recordCutoff(number: String) {
        viewModelScope.launch { repository.record(number, disqualified = false) }
    }

    fun recordDisqualification(number: String) {
        viewModelScope.launch { repository.record(number, disqualified = true) }
    }

    fun loadSettings(): Settings = app.settingsStore.load()

    fun saveSettings(settings: Settings) = app.settingsStore.save(settings)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
