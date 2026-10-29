package com.dchernykh.chronometer.source

import kotlinx.coroutines.flow.Flow

/** A device's events as a cold flow; it ends or throws when the device fails (FR-005). */
fun interface CutoffSource {
    fun events(): Flow<SourceEvent>
}

/** Why an event cannot be used; the screen says it in the referee's language. */
enum class EventProblem {
    NO_CHIP,

    /** Before 2000 (a device clock never set) or from 2100 on (spec FR-006). */
    TIME_OUT_OF_RANGE,
}

/** One event a source reports, as the desktop connector protocol defines them (FR-003). */
sealed interface SourceEvent {
    /** Why this event cannot be used, or null when it can. */
    fun problem(): EventProblem? = null

    data class ChipRead(
        val chip: String,
        /** On the phone's clock, when the device knows it. */
        val timeMs: Long? = null,
        val deviceTime: String = "",
        val antenna: String = "",
        val rssi: Double? = null,
        val reader: String = "",
    ) : SourceEvent {
        override fun problem(): EventProblem? = if (chip.isBlank()) EventProblem.NO_CHIP else timeProblem(timeMs)
    }

    data class BeamBreak(
        val seq: Long,
        /** On the phone's clock, when the device knows it. */
        val timeMs: Long? = null,
        val deviceTime: String = "",
    ) : SourceEvent {
        override fun problem(): EventProblem? = timeProblem(timeMs)
    }

    data class Status(
        val state: String,
        val detail: String = "",
    ) : SourceEvent

    data class Error(
        val message: String,
        val fatal: Boolean = false,
    ) : SourceEvent
}

/** The first moment an event can have: 2000-01-01, as the desktop protocol decides. */
const val EARLIEST_EVENT_MS = 946_684_800_000L

/** The moment no event reaches: 2100-01-01. */
const val LATEST_EVENT_MS = 4_102_444_800_000L

private fun timeProblem(timeMs: Long?): EventProblem? =
    if (timeMs == null || timeMs in EARLIEST_EVENT_MS until LATEST_EVENT_MS) {
        null
    } else {
        EventProblem.TIME_OUT_OF_RANGE
    }
