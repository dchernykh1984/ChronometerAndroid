package com.dchernykh.chronometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Beam breaks waiting for the referee to give each a number, oldest first. */
@Dao
interface BeamDao {
    @Insert
    suspend fun insertBreak(pending: PendingBreakEntity): Long

    @Query("SELECT * FROM pending_breaks ORDER BY id")
    fun observeBreaks(): Flow<List<PendingBreakEntity>>

    /** The oldest break from [id] on: the one shown, or the next if a tap took it first. */
    @Query("SELECT * FROM pending_breaks WHERE id >= :id ORDER BY id LIMIT 1")
    suspend fun oldestBreakFrom(id: Long): PendingBreakEntity?

    @Query("DELETE FROM pending_breaks WHERE id = :id")
    suspend fun deleteBreak(id: Long): Int

    @Query("DELETE FROM pending_breaks")
    suspend fun clearBreaks()
}
