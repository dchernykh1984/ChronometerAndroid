package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cutoff event kinds, written verbatim into the `number#time#event#` line. */
object CutoffEvent {
    /** A regular crossing at a control point (a lap). */
    const val NEXT_LAP = "nextLap"

    /** A disqualification. */
    const val DSQ = "DSQ"

    /** A finish crossing. Recorded by a regular press when finish-mode is on. */
    const val FINISH = "finish"

    /** Which event a regular cutoff press records, per the finish-mode toggle. */
    fun lapEvent(finishMode: Boolean): String = if (finishMode) FINISH else NEXT_LAP

    /**
     * The disqualification event: bare [DSQ] when no reason is given (backward compatible)
     * or `DSQ: <reason>`. `#` and line breaks are stripped from the reason so it can never
     * break the `#`-delimited `number#time#event#` record; surrounding space is trimmed.
     */
    fun dsq(reason: String = ""): String {
        val cleaned =
            reason
                .replace('#', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
        return if (cleaned.isEmpty()) DSQ else "$DSQ: $cleaned"
    }
}

/**
 * One recorded crossing. Stored durably in Room before anything else happens, so
 * a crash or a failed file write / upload can never lose a cutoff.
 */
@Entity(tableName = "cutoffs")
data class CutoffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val timeStr: String,
    val event: String,
    val createdAt: Long,
) {
    /** Line format shared by the local files and the server payload: "number#time#event#". */
    fun toItem(): String = "$number#$timeStr#$event#"
}
