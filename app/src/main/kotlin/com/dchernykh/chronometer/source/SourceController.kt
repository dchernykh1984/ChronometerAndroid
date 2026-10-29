package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.chips.ChipMap
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.chosenDevice
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A batch failing this many times (about 3 minutes of pauses) is dropped, not tried forever. */
private const val STORE_TRIES = 10

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
    /** The phone's clock: an event its device did not time is timed by it as it arrives. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Which competition an event arrives in; the sink drops one from a competition since replaced. */
    private val competition: () -> Long = { 0L },
    /** Where events are stored: it outlives a stop, so what arrived is stored all the same. */
    private val storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Stores events taken in the competition given; the last argument is true on a batch's
     * first try only, so its raw lines are written once, before anything can fail.
     */
    private val sink: suspend (List<SourceEvent>, Long, Boolean) -> Unit,
) {
    /** The source behind a device; the app gives the test chips its chip map. */
    var sourceFor: (DeviceId) -> CutoffSource? = { builtInSource(it) { ChipMap() } }

    private val statusState = MutableStateFlow(SourceStatus())

    // A run writes its status only while it is the current one: stop() runs on the main
    // thread, the run's status on a worker, and a late write must not undo the stop.
    private val statusLock = Any()
    private var run = 0L
    private val deviceState = MutableStateFlow<DeviceId?>(null)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    // App-wide, not a run's: a consumer of a run before a restart that still cannot store
    // shows on the run after it.
    private val storeError = MutableStateFlow<SourceError?>(null)
    private var consumer: Job? = null

    val status: StateFlow<SourceStatus> = statusState.asStateFlow()

    /** The device running, or null by hand or with timing mode off. */
    val device: StateFlow<DeviceId?> = deviceState.asStateFlow()

    /** Run the chosen source in [scope] until [stop]; the settings are read now. */
    fun start(scope: CoroutineScope) {
        stop()
        val thisRun = synchronized(statusLock) { run }
        this.scope = scope
        val device = chosenDevice()
        deviceState.value = device
        val source = device?.takeIf { it.available }?.let(sourceFor)
        if (device == null || source == null) {
            statusState.value =
                SourceStatus(state = if (device == null) SourceState.Stopped else SourceState.NotAvailable)
            return
        }
        val runner = SourceRunner(source, now)
        // Unlimited: the source never waits for Room, whatever a burst of reads holds.
        val channel = Channel<Taken>(Channel.UNLIMITED)
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
                    store(batch)
                }
            }
        job =
            scope.launch {
                launch {
                    combine(runner.status, storeError) { status, error ->
                        if (error == null) status else status.copy(lastError = error)
                    }.collect { status ->
                        synchronized(statusLock) {
                            if (run == thisRun) {
                                statusState.value = status
                            }
                        }
                    }
                }
                runner.run { channel.send(Taken(competition(), it.timedOnArrival(wallClock()))) }
            }
        // Closed once the run has ended, so an event it was handing on as it stopped is kept.
        job?.invokeOnCompletion { channel.close() }
    }

    /**
     * Hand [batch] on until it is stored. A failure -- a full disk, say -- shows as the
     * source's last error and the batch is tried again after a pause growing from 1 s to
     * 30 s, newer events waiting behind it, and the app keeps running. One failing the same
     * way for about 3 minutes is dropped from Room's side so the events behind it are stored;
     * its raw lines, written on the first try, keep what the device reported.
     */
    private suspend fun store(batch: List<Taken>) {
        // One competition and one kind of event a call: a retry repeats only what failed, so a
        // beam break stored beside chip reads that failed is not queued twice.
        for ((group, events) in batch.groupBy({ it.competition to it.event.javaClass }, { it.event })) {
            storeTakenIn(group.first, events)
        }
    }

    @Suppress("TooGenericExceptionCaught") // whatever storing throws, the batch is kept
    private suspend fun storeTakenIn(
        takenIn: Long,
        events: List<SourceEvent>,
    ) {
        var pause: Long? = null
        var failure: Throwable? = null
        repeat(STORE_TRIES) { tried ->
            // Taken in a competition since replaced: its tables are gone, and so are its events
            // and their error. The sink makes sure under the lock, for a batch on its way.
            if (takenIn != competition()) {
                storeError.value = null
                return
            }
            try {
                sink(events, takenIn, tried == 0)
                // A drop stays said until a new competition: what it lost is lost.
                storeError.update { it as? SourceError.Dropped }
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (thrown: Throwable) {
                failure = thrown
                storeError.value = SourceError.NotStored(thrown.message ?: thrown.javaClass.simpleName)
                // No pause after the last try: the events behind it have waited long enough.
                if (tried < STORE_TRIES - 1) {
                    pause = nextPause(pause, 0)
                    delay(pause)
                }
            }
        }
        // Failing the same way for minutes: kept no longer, so the events behind it are stored.
        val why = failure?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        storeError.value = SourceError.Dropped(events.size, why)
    }

    /** A new competition: what the last one could not store is no longer said. */
    fun startOver() {
        storeError.value = null
    }

    /**
     * Start again if it is running and the settings now choose another device; a save that
     * changed anything else leaves a running device alone, mid-race.
     */
    fun restart() {
        val scope = scope ?: return
        if (chosenDevice() != deviceState.value) {
            start(scope)
        }
    }

    /** The device the settings choose; null by hand. */
    private fun chosenDevice(): DeviceId? = settingsStore.load().chosenDevice

    fun stop() {
        job?.cancel()
        job = null
        // What the source sent is stored all the same: its channel closes as the run ends,
        // and the consumer ends once it has it all.
        scope = null
        deviceState.value = null
        synchronized(statusLock) {
            run++
            statusState.value = SourceStatus()
        }
    }
}

/**
 * A read or a break its device did not time, timed now (spec FR-006): as it arrives, not
 * when it is stored, which a burst or a store tried again can put seconds later.
 */
private fun SourceEvent.timedOnArrival(nowMs: Long): SourceEvent =
    when (this) {
        is SourceEvent.ChipRead -> if (timeMs == null) copy(timeMs = nowMs) else this
        is SourceEvent.BeamBreak -> if (timeMs == null) copy(timeMs = nowMs) else this
        else -> this
    }

/** An event and the competition it was taken in. */
private class Taken(
    val competition: Long,
    val event: SourceEvent,
)
