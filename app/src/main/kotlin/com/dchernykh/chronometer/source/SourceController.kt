package com.dchernykh.chronometer.source

import androidx.annotation.VisibleForTesting
import com.dchernykh.chronometer.chips.ChipMap
import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** At most this many waiting events go on together, so a burst costs one snapshot. */
private const val BATCH = 200

/** The source behind each device; null while a device is not available. */
fun builtInSource(
    device: DeviceId,
    chipMap: () -> ChipMap,
): CutoffSource? =
    when (device) {
        DeviceId.TEST_BEAM -> TestBeamSource()
        DeviceId.TEST_CHIPS -> TestChipsSource(chipMap)
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
    /** The chip map in use, for the test chips. */
    chipMap: () -> ChipMap = { ChipMap() },
    /** Where events are stored: it outlives a stop, so what arrived is stored all the same. */
    private val storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sink: suspend (List<SourceEvent>) -> Unit,
) {
    @VisibleForTesting
    var sourceFor: (DeviceId) -> CutoffSource? = { builtInSource(it, chipMap) }

    private val statusState = MutableStateFlow(SourceStatus())
    private val deviceState = MutableStateFlow<DeviceId?>(null)
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var events: Channel<SourceEvent>? = null
    private var consumer: Job? = null

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
        val channel = Channel<SourceEvent>(Channel.UNLIMITED)
        val storeError = MutableStateFlow<SourceError?>(null)
        events = channel
        val previous = consumer
        // After the run before it has stored everything it took, so events keep their order.
        consumer =
            storeScope.launch {
                previous?.join()
                for (first in channel) {
                    val batch = mutableListOf(first)
                    while (batch.size < BATCH) {
                        batch += channel.tryReceive().getOrNull() ?: break
                    }
                    store(batch, storeError)
                }
            }
        job =
            scope.launch {
                launch {
                    combine(runner.status, storeError) { status, error ->
                        if (error == null) status else status.copy(lastError = error)
                    }.collect { statusState.value = it }
                }
                runner.run { channel.send(it) }
            }
    }

    /**
     * Hand [batch] on until it is stored. A failure -- a full disk, say -- shows as the
     * source's last error and the batch is tried again after a pause growing from 1 s to
     * 30 s, newer events waiting behind it: nothing is dropped, and the app keeps running.
     */
    @Suppress("TooGenericExceptionCaught") // whatever storing throws, the batch is kept
    private suspend fun store(
        batch: List<SourceEvent>,
        error: MutableStateFlow<SourceError?>,
    ) {
        var pause: Long? = null
        while (true) {
            try {
                sink(batch)
                error.value = null
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error.value = SourceError.NotStored(failure.message ?: failure.javaClass.simpleName)
                pause = nextPause(pause, 0)
                delay(pause)
            }
        }
    }

    /** Start again with the settings as they are now, if it is running. */
    fun restart() {
        scope?.let(::start)
    }

    fun stop() {
        job?.cancel()
        job = null
        // What the source sent is stored all the same: the consumer ends once it has it all.
        events?.close()
        events = null
        scope = null
        deviceState.value = null
        statusState.value = SourceStatus()
    }
}
