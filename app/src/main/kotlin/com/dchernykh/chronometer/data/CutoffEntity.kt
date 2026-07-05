package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cutoff event kinds, written verbatim into the `number#time#event#` line. */
object CutoffEvent {
    /** A regular crossing at a control point (a lap). */
    const val NEXT_LAP = "nextLap"

    /** A disqualification. */
    const val DSQ = "DSQ"

    /** A finish crossing. Not exposed in the UI yet. */
    const val FINISH = "finish"
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
