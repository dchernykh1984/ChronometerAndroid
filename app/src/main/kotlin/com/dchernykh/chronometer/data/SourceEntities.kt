package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chip read as its device reported it, kept in arrival order (spec FR-007), with the
 * bib whose passes hold it. The index covers the waiting reads' queries: the unknown chips
 * are grouped from it alone, however many reads a stray chip has.
 */
@Entity(tableName = "chip_reads", indices = [Index("bib", "chip", "timeMs")])
data class ChipReadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised: upper case, ASCII letters and digits only. */
    val chip: String,
    /** `D H:M:S.mmm`, as the cutoff it may become is written. */
    val timeStr: String,
    /** [timeStr] read back, on the scale the pass rule compares. */
    val timeMs: Long,
    val deviceTime: String = "",
    val antenna: String = "",
    val reader: String = "",
    val rssi: Double? = null,
    /** Null while no map binds the chip: the read waits for one that does (FR-011). */
    val bib: String? = null,
)

/**
 * A bib's chip reads less than the dead time apart (spec FR-010). Every pass is made
 * together with its cutoff, so a pass that exists has had one and never gets another.
 */
@Entity(tableName = "passes", indices = [Index("bib")])
data class PassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bib: String,
    val firstMs: Long,
    val lastMs: Long,
)

/** A beam break waiting for the referee to give it a number, oldest first. */
@Entity(tableName = "pending_breaks")
data class PendingBreakEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val timeStr: String,
    val deviceTime: String = "",
)

/** A chip whose reads wait for a map: when it was first and last read, and how often. */
data class ChipSeen(
    val chip: String,
    val firstMs: Long,
    val lastMs: Long,
    val reads: Int,
)
