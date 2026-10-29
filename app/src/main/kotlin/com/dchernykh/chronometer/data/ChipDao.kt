package com.dchernykh.chronometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Chip reads as devices reported them; a read waits, with no bib, until a map binds its chip. */
@Dao
interface ChipDao {
    @Insert
    suspend fun insertReads(reads: List<ChipReadEntity>): List<Long>

    /** Waiting reads of the given chips in time order; callers keep the list under 900 ids. */
    @Query("SELECT * FROM chip_reads WHERE bib IS NULL AND chip IN (:chips) ORDER BY timeMs, id")
    suspend fun waitingReadsOf(chips: List<String>): List<ChipReadEntity>

    /** Chips with waiting reads: the unknown chips, and the work for a newer map. */
    @Query(
        "SELECT chip, MIN(timeMs) AS firstMs, MAX(timeMs) AS lastMs, COUNT(*) AS reads " +
            "FROM chip_reads WHERE bib IS NULL GROUP BY chip ORDER BY chip",
    )
    fun observeWaiting(): Flow<List<ChipSeen>>

    @Query("SELECT DISTINCT chip FROM chip_reads WHERE bib IS NULL")
    suspend fun waitingChips(): List<String>

    /** Give the waiting reads of [chip] to [bib]. */
    @Query("UPDATE chip_reads SET bib = :bib WHERE bib IS NULL AND chip = :chip")
    suspend fun attribute(
        chip: String,
        bib: String,
    ): Int

    @Query("DELETE FROM chip_reads")
    suspend fun clearReads()
}
