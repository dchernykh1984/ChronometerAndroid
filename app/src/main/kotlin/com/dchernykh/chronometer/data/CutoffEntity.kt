package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dchernykh.chronometer.io.RawFiles

/**
 * The most characters a cutoff's number or DSQ reason keeps: far below the 2000 the site takes
 * for an item, as one longer has the site refuse every later upload of the device (HTTP 400).
 */
const val CUTOFF_TEXT_LIMIT = 200

/** [text] as a cutoff's number or DSQ reason: one field of the line ([RawFiles.field]), cut to [CUTOFF_TEXT_LIMIT]. */
fun cutoffText(text: String): String = RawFiles.field(text).take(CUTOFF_TEXT_LIMIT).trimEnd()

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
     * or `DSQ: <reason>`. `#` and every line break a reader may split at are stripped from
     * the reason, as from a number, so it can never break the `#`-delimited
     * `number#time#event#` record; surrounding space is trimmed, and the reason keeps at most
     * [CUTOFF_TEXT_LIMIT] characters.
     */
    fun dsq(reason: String = ""): String {
        val cleaned = cutoffText(reason)
        return if (cleaned.isEmpty()) DSQ else "$DSQ: $cleaned"
    }

    /** [event] as an edit stores it: a DSQ reason stored before the cap cut to it, as [dsq] makes one. */
    fun capped(event: String): String = if (event.startsWith("$DSQ: ")) dsq(event.removePrefix("$DSQ: ")) else event
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
