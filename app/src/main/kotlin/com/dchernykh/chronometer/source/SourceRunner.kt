package com.dchernykh.chronometer.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a source is doing (data model, "Source state"). */
sealed interface SourceState {
    data object Stopped : SourceState

    data object Running : SourceState

    data class Restarting(
        val inSeconds: Int,
    ) : SourceState

    /** A stub device: listed, never started. */
    data object NotAvailable : SourceState
}

/** The last thing that went wrong, said on screen in the referee's language. */
sealed interface SourceError {
    /** An error the device itself reported, as it worded it. */
    data class Device(
        val message: String,
    ) : SourceError

    /** An event the app could not use. */
    data class Invalid(
        val problem: EventProblem,
    ) : SourceError

    /** The source failed, as its exception words it. */
    data class Failed(
        val message: String,
    ) : SourceError

    /** The source's events came to an end. */
    data object Ended : SourceError

    /** Events could not be stored; they are tried again. */
    data class NotStored(
        val message: String,
    ) : SourceError
}

data class SourceStatus(
    val state: SourceState = SourceState.Stopped,
    val invalidEvents: Int = 0,
    val lastError: SourceError? = null,
)

private const val FIRST_PAUSE_MS = 1_000L
private const val LONGEST_PAUSE_MS = 30_000L
private const val STEADY_MS = 30_000L
private const val MS_PER_SECOND = 1_000L

/** The pause before the next start, after a run of [ranMs] that failed (spec FR-005). */
fun nextPause(
    previousMs: Long?,
    ranMs: Long,
): Long =
    if (previousMs == null || ranMs >= STEADY_MS) {
        FIRST_PAUSE_MS
    } else {
        minOf(previousMs * 2, LONGEST_PAUSE_MS)
    }

/** A device gave up: the run ends and the source starts again after the pause. */
private class GaveUp(
    message: String,
) : Exception(message)

/**
 * Runs one source until cancelled: usable events go to the consumer in order, invalid
 * ones are counted, and a source that fails, ends or gives up is started again after a
 * pause that grows from 1 s to 30 s while it keeps failing. [now] is a millisecond
 * clock, the test scheduler's in tests.
 */
class SourceRunner(
    private val source: CutoffSource,
    private val now: () -> Long,
) {
    private val statusState = MutableStateFlow(SourceStatus())

    val status: StateFlow<SourceStatus> = statusState.asStateFlow()

    suspend fun run(onEvent: suspend (SourceEvent) -> Unit) {
        var pause: Long? = null
        try {
            while (true) {
                val started = now()
                statusState.update { it.copy(state = SourceState.Running) }
                val failure = runOnce(onEvent)
                pause = nextPause(pause, now() - started)
                statusState.update {
                    it.copy(
                        state = SourceState.Restarting((pause / MS_PER_SECOND).toInt()),
                        lastError = it.lastError.takeIf { failure == null } ?: failure,
                    )
                }
                delay(pause)
            }
        } finally {
            statusState.update { it.copy(state = SourceState.Stopped) }
        }
    }

    /** One run of the source; what made it end, or null when it just ended. */
    @Suppress("TooGenericExceptionCaught") // a device library may throw anything
    private suspend fun runOnce(onEvent: suspend (SourceEvent) -> Unit): SourceError? =
        try {
            source.events().collect { event -> take(event, onEvent) }
            SourceError.Ended
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: GaveUp) {
            null // the device's own words are the last error already
        } catch (failure: Exception) {
            SourceError.Failed(failure.message ?: failure.javaClass.simpleName)
        }

    private suspend fun take(
        event: SourceEvent,
        onEvent: suspend (SourceEvent) -> Unit,
    ) {
        val problem = event.problem()
        when {
            problem != null ->
                statusState.update {
                    it.copy(invalidEvents = it.invalidEvents + 1, lastError = SourceError.Invalid(problem))
                }
            event is SourceEvent.Error -> {
                statusState.update { it.copy(lastError = SourceError.Device(event.message)) }
                if (event.fatal) {
                    throw GaveUp(event.message)
                }
            }
            else -> onEvent(event)
        }
    }
}
