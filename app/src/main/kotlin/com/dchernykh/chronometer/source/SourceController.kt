package com.dchernykh.chronometer.source

import androidx.annotation.VisibleForTesting
import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** At most this many waiting events go on together, so a burst costs one snapshot. */
private const val BATCH = 200

/** The source behind each device; null while a device is not available. */
fun builtInSource(device: DeviceId): CutoffSource? =
    when (device) {
        DeviceId.TEST_BEAM -> TestBeamSource()
        else -> null
    }

/**
 * Runs the source the settings choose while timing mode is on (spec FR-002) and hands
 * its events on, in arrival order and in batches, to [sink]. App-wide: the timing-mode
 * service starts and stops it, the screen shows its state.
 */
class SourceController(
    private val settingsStore: SettingsStore,
    private val now: () -> Long,
    private val sink: suspend (List<SourceEvent>) -> Unit,
) {
    @VisibleForTesting
    var sourceFor: (DeviceId) -> CutoffSource? = ::builtInSource

    private val statusState = MutableStateFlow(SourceStatus())
    private val deviceState = MutableStateFlow<DeviceId?>(null)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    val status: StateFlow<SourceStatus> = statusState.asStateFlow()

    /** The device running, or null by hand or with timing mode off. */
    val device: StateFlow<DeviceId?> = deviceState.asStateFlow()

    /** Run the chosen source in [scope] until [stop]; the settings are read now. */
    fun start(scope: CoroutineScope) {
        stop()
        this.scope = scope
        val settings = settingsStore.load()
        val device =
            when (settings.cutoffSource) {
                SourceKind.HAND -> null
                SourceKind.BEAM -> settings.beamDevice
                SourceKind.CHIPS -> settings.chipsDevice
            }
        deviceState.value = device
        val source = device?.takeIf { it.available }?.let(sourceFor)
        if (device == null || source == null) {
            statusState.value =
                SourceStatus(state = if (device == null) SourceState.Stopped else SourceState.NotAvailable)
            return
        }
        val runner = SourceRunner(source, now)
        // Unlimited: the source never waits for Room, whatever a burst of reads holds.
        val events = Channel<SourceEvent>(Channel.UNLIMITED)
        job =
            scope.launch {
                launch { runner.status.collect { statusState.value = it } }
                launch {
                    for (first in events) {
                        val batch = mutableListOf(first)
                        while (batch.size < BATCH) {
                            batch += events.tryReceive().getOrNull() ?: break
                        }
                        sink(batch)
                    }
                }
                runner.run { events.send(it) }
            }
    }

    /** Start again with the settings as they are now, if it is running. */
    fun restart() {
        scope?.let(::start)
    }

    fun stop() {
        job?.cancel()
        job = null
        scope = null
        deviceState.value = null
        statusState.value = SourceStatus()
    }
}
