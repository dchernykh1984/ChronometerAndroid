package com.dchernykh.chronometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CutoffDao {
    @Insert
    suspend fun insert(cutoff: CutoffEntity): Long

    /** Correct a mis-recorded crossing: only the number and time are editable. */
    @Query("UPDATE cutoffs SET number = :number, timeStr = :timeStr WHERE id = :id")
    suspend fun updateNumberAndTime(
        id: Long,
        number: String,
        timeStr: String,
    )

    /** Full history in recording order, for file snapshots and uploads. */
    @Query("SELECT * FROM cutoffs ORDER BY id ASC")
    suspend fun getAll(): List<CutoffEntity>

    /** Newest first, for the read-only on-screen log. */
    @Query("SELECT * FROM cutoffs ORDER BY id DESC")
    fun observeNewestFirst(): Flow<List<CutoffEntity>>

    /** Clear all cutoffs when starting a new competition. Backups on disk stay. */
    @Query("DELETE FROM cutoffs")
    suspend fun deleteAll()
}
