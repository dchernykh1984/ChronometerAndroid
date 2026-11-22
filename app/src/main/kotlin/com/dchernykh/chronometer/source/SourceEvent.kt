package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.chips.normaliseChip
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
        // Chips are compared normalised: an id without an ASCII letter or digit is no id.
        override fun problem(): EventProblem? =
            if (normaliseChip(chip).isEmpty()) EventProblem.NO_CHIP else timeProblem(timeMs)
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

    /**
     * A beam board's word on itself, for the screen to say in the referee's language: a
     * trouble, or null once all is well again, which ends a lasting one. [board] names it
     * among the boards picked (spec 003); empty, the source's one board.
     */
    data class Board(
        val trouble: BoardTrouble?,
        val board: String = "",
    ) : SourceEvent
}

/**
 * What the referee must know of a beam board (the beam board contract, step 4). A lasting
 * one holds until the board says all is well; a notice stays until another error.
 */
sealed interface BoardTrouble {
    val lasting: Boolean get() = true

    /** No beam board on USB. */
    data object NotFound : BoardTrouble

    /** Beam boards chosen, and none picked in the list of the boards around (spec 003). */
    data object NoBoardPicked : BoardTrouble

    /**
     * The board picked twice, on its USB and its Bluetooth, and read on the link that said
     * HELLO first only, [other]'s: each of its breaks would be two cutoffs (spec 003).
     */
    data class Twin(
        val other: String,
    ) : BoardTrouble

    /** The Bluetooth beam with no board named in the settings: none is read (contract, step 1). */
    data object NotNamed : BoardTrouble

    /** No board of the name in the settings in Bluetooth range. */
    data class NotInRange(
        val name: String,
    ) : BoardTrouble

    /** The phone's Bluetooth is off. */
    data object BluetoothOff : BoardTrouble

    /** Android waits for the referee to let the app use Bluetooth. */
    data object NoBluetoothLeave : BoardTrouble

    /** Android waits for the referee to let the app use the board. */
    data object NoPermission : BoardTrouble

    /** The board could not be opened: [why], with the system's words for a reason of its own. */
    data class NotOpened(
        val why: OpenFailure,
        val words: String = "",
    ) : BoardTrouble

    /** The board's link was lost: unplugged, or its port failed. */
    data object Lost : BoardTrouble

    /** A PING unanswered for 6 s: the board's program has stopped, its port still open. */
    data object Silent : BoardTrouble

    /** The beam blocked for longer than any rider takes: no break can be seen. */
    data object Blocked : BoardTrouble

    /** No state of the light holds: it flickers on the sensor, no break can be seen. */
    data object Unsteady : BoardTrouble

    /** The board runs another firmware than the one the app is built for. */
    data class OtherFirmware(
        val runs: String,
        val wanted: String,
    ) : BoardTrouble {
        override val lasting: Boolean get() = false
    }

    /** Breaks the board counted that never came: its link was down as they did. */
    data class BreaksMissing(
        val first: Long,
        val last: Long,
    ) : BoardTrouble {
        override val lasting: Boolean get() = false
    }

    /** Breaks timed by when their lines came, the board's clock not read. */
    data class ClockNotRead(
        val breaks: Int,
    ) : BoardTrouble {
        override val lasting: Boolean get() = false
    }
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

/** Why a beam board could not be opened; OTHER is the system's reason, said in its words. */
enum class OpenFailure {
    /** Another app has the board's port. */
    HELD,

    /** The board shows no serial port: no MicroPython on it, say. */
    NO_PORT,

    /** Android would not open it: unplugged meanwhile, or its leave taken back. */
    REFUSED,
    OTHER,
}
