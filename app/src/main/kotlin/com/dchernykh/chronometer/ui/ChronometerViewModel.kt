package com.dchernykh.chronometer.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.chips.StartProtocol
import com.dchernykh.chronometer.data.ChipSeen
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.PendingBreakEntity
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.source.SourceStatus
import com.dchernykh.chronometer.startprotocol.Download
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// One function a screen action: the screens reach the app through this one model.
@Suppress("TooManyFunctions")
class ChronometerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ChronometerApp
    private val repository = app.cutoffRepository
    private val beam = app.beamRepository
    private val chips = app.chipRepository

    private val settingsState = MutableStateFlow(app.settingsStore.load())

    /** Reactive settings so the UI (input type, theme, language) updates on save. */
    val settings: StateFlow<Settings> = settingsState.asStateFlow()

    val cutoffs: StateFlow<List<CutoffEntity>> =
        repository.cutoffs.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList(),
        )

    /** Diagnostics (do not affect recording): last file outcome + upload state. */
    val backupFailed: StateFlow<Boolean> =
        combine(repository.backupFailed, beam.rawFailed, chips.rawFailed) { snapshot, breaks, reads ->
            snapshot || breaks || reads
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** `start.txt` as last read, for the chips line and the chips screen; null before. */
    val startProtocol: StateFlow<StartProtocol?> = chips.protocol

    private val downloadState = MutableStateFlow<Download?>(null)

    /** The last start list download, for its line in the settings; null before the first. */
    val download: StateFlow<Download?> = downloadState.asStateFlow()

    /** Fetch the start list now; a press while one runs does nothing. */
    fun downloadStartProtocol() {
        if (downloadState.value == Download.Running) {
            return
        }
        downloadState.value = Download.Running
        viewModelScope.launch { downloadState.value = chips.download() }
    }

    /** Chips read that the map does not hold, by id. */
    val unknownChips: StateFlow<List<ChipSeen>> =
        chips.unknown.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    // A map reload or a discard that fails (Room, say) changes nothing and is logged: it must
    // not end the app mid-race. Recording a cutoff is not run under it: losing one silently
    // would be worse.
    private val logged = CoroutineExceptionHandler { _, failure -> Log.w(TAG, "chips or beam action", failure) }

    init {
        viewModelScope.launch(logged) { chips.ensureMap() }
    }

    /** Beam breaks waiting for a number, oldest first. */
    val pendingBreaks: StateFlow<List<PendingBreakEntity>> =
        beam.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** The cutoff source's state, for its line on the main screen. */
    val sourceStatus: StateFlow<SourceStatus> = app.sourceController.status

    val uploadDiagnostics: StateFlow<UploadDiagnostics> =
        combine(repository.uploadWorkInfo, app.uploadErrorStore.error, ::uploadDiagnosticsOf)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UploadDiagnostics())

    /**
     * The oldest waiting beam break, the one the button shows, takes the number at its time,
     * whatever the source is now; with none waiting, the cutoff is at the tap.
     */
    fun recordCutoff(number: String) {
        // The tap's moment and the break the button shows, taken now: the beam check below may
        // wait for a batch, and a break stored meanwhile is the next rider's, not this number's.
        val tappedAt = System.currentTimeMillis()
        val shown = pendingBreaks.value.firstOrNull()?.id
        val event = CutoffEvent.lapEvent(settingsState.value.finishMode)
        viewModelScope.launch {
            if (shown == null || !beam.assign(shown, number, event)) {
                repository.record(number, event, tappedAt)
            }
        }
    }

    /** Read start.txt again, after the referee put a new one in the data folder. */
    fun reloadStartProtocol() {
        viewModelScope.launch(logged) { chips.reloadMap() }
    }

    /** A false beam break leaves the queue; beam_breaks.txt keeps it. */
    fun discardBreak(id: Long) {
        viewModelScope.launch(logged) { beam.discard(id) }
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
        val folderBefore = settingsState.value.folderPath
        app.settingsStore.save(settings)
        // Re-read so normalized values (generated uuid, default folder) propagate.
        settingsState.value = app.settingsStore.load()
        // Another data folder holds another event's start.txt, or none: the map before is not it.
        if (settingsState.value.folderPath != folderBefore) {
            viewModelScope.launch(logged) { chips.reloadMap(keepMap = false) }
        }
        // A device chosen anew while timing mode is on runs at once.
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
        const val TAG = "ChronometerViewModel"
    }
}
