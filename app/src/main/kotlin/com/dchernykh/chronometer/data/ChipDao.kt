package com.dchernykh.chronometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Chip reads as devices reported them, and the chips whose reads are in passes. */
@Dao
interface ChipDao {
    @Insert
    suspend fun insertReads(reads: List<ChipReadEntity>): List<Long>

    /** Reads of the given chips in time order; callers keep the list under 900 ids. */
    @Query("SELECT * FROM chip_reads WHERE chip IN (:chips) ORDER BY timeMs, id")
    suspend fun readsOf(chips: List<String>): List<ChipReadEntity>

    /** Chips read but in no pass yet, for the unknown chips and a newer map. */
    @Query(
        "SELECT chip, MIN(timeMs) AS firstMs, MAX(timeMs) AS lastMs, COUNT(*) AS reads " +
            "FROM chip_reads WHERE chip NOT IN (SELECT chip FROM attributed_chips) " +
            "GROUP BY chip ORDER BY chip",
    )
    fun observeUnattributed(): Flow<List<ChipSeen>>

    @Query(
        "SELECT DISTINCT chip FROM chip_reads " +
            "WHERE chip NOT IN (SELECT chip FROM attributed_chips)",
    )
    suspend fun unattributedChips(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attribute(chips: List<AttributedChipEntity>)

    @Query("DELETE FROM chip_reads")
    suspend fun clearReads()

    @Query("DELETE FROM attributed_chips")
    suspend fun clearAttributed()
}
