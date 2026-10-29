package com.dchernykh.chronometer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.source.SourceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChronometerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ChronometerApp
    private val repository =
        CutoffRepository(app, app.database.cutoffDao(), app.settingsStore, app.backupWriter)

    private val settingsState = MutableStateFlow(app.settingsStore.load())

    /** Reactive settings so the UI (input type, theme, language) updates on save. */
    val settings: StateFlow<Settings> = settingsState.asStateFlow()

    val cutoffs: StateFlow<List<CutoffEntity>> =
        repository.cutoffs.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList(),
        )

    /** Diagnostics (do not affect recording): last backup outcome + upload state. */
    val backupFailed: StateFlow<Boolean> = repository.backupFailed

    /** The cutoff source's state, for its line on the main screen. */
    val sourceStatus: StateFlow<SourceStatus> = app.sourceController.status

    val uploadDiagnostics: StateFlow<UploadDiagnostics> =
        combine(repository.uploadWorkInfo, app.uploadErrorStore.error, ::uploadDiagnosticsOf)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UploadDiagnostics())

    fun recordCutoff(number: String) {
        val event = CutoffEvent.lapEvent(settingsState.value.finishMode)
        viewModelScope.launch { repository.record(number, event) }
    }

    fun recordDisqualification(
        number: String,
        reason: String = "",
    ) {
        viewModelScope.launch { repository.record(number, CutoffEvent.dsq(reason)) }
    }

    /** Correct the number/time of an already-recorded cutoff, keeping its event. */
    fun editCutoff(
        id: Long,
        number: String,
        timeStr: String,
    ) {
        viewModelScope.launch { repository.editCutoff(id, number, timeStr) }
    }

    fun loadSettings(): Settings = app.settingsStore.load()

    fun deleteCutoff(
        id: Long,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch { onDone(repository.deleteCutoff(id)) }
    }

    fun saveSettings(settings: Settings) {
        app.settingsStore.save(settings)
        // Re-read so normalized values (generated uuid, default folder) propagate.
        settingsState.value = app.settingsStore.load()
        // A source chosen anew while timing mode is on runs at once.
        app.sourceController.restart()
        // Flush cutoffs recorded while sending was off/misconfigured.
        viewModelScope.launch { repository.uploadPendingCutoffs() }
    }

    /** Reset cutoffs/results/token/point for a new competition, keeping backups. */
    fun startNewCompetition(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.startNewCompetition()
            if (success) {
                settingsState.value = app.settingsStore.load()
            }
            onDone(success)
        }
    }

    /** Current on-disk size of the data folder (results + backups), in bytes. */
    suspend fun dataFolderSizeBytes(): Long = repository.dataFolderSizeBytes()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
