package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded crossing. Stored durably in Room before anything else happens, so
 * a crash or a failed file write / upload can never lose a cutoff.
 */
@Entity(tableName = "cutoffs")
data class CutoffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val timeStr: String,
    val disqualified: Boolean,
    val createdAt: Long,
) {
    /**
     * Line format shared by the local files and the server payload:
     * "number#time#" for a normal cutoff, "number#time#DSQ#" for a disqualification.
     */
    fun toItem(): String = if (disqualified) "$number#$timeStr#DSQ#" else "$number#$timeStr#"
}
