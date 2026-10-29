package com.dchernykh.chronometer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One chip read as its device reported it, kept in arrival order (spec FR-007). */
@Entity(tableName = "chip_reads", indices = [Index("chip")])
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
)

/**
 * A bib's chip reads less than the dead time apart (spec FR-010). Every pass is made
 * together with its cutoff, which [issued] records, so it never gets a second one.
 */
@Entity(tableName = "passes", indices = [Index("bib")])
data class PassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bib: String,
    val firstMs: Long,
    val lastMs: Long,
    val issued: Boolean,
)

/** A chip whose reads have gone into passes; a newer map never replays them. */
@Entity(tableName = "attributed_chips")
data class AttributedChipEntity(
    @PrimaryKey val chip: String,
)

/** A beam break waiting for the referee to give it a number, oldest first. */
@Entity(tableName = "pending_breaks")
data class PendingBreakEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val timeStr: String,
    val deviceTime: String = "",
)

/** A chip no pass holds yet: when it was first and last read, and how often. */
data class ChipSeen(
    val chip: String,
    val firstMs: Long,
    val lastMs: Long,
    val reads: Int,
)
