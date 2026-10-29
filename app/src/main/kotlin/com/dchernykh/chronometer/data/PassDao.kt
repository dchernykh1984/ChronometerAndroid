package com.dchernykh.chronometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Each bib's passes: its chip reads less than the dead time apart. */
@Dao
interface PassDao {
    @Query("SELECT * FROM passes WHERE bib = :bib ORDER BY firstMs")
    suspend fun passesOf(bib: String): List<PassEntity>

    @Insert
    suspend fun insertPass(pass: PassEntity): Long

    @Update
    suspend fun updatePass(pass: PassEntity)

    @Query("DELETE FROM passes WHERE id IN (:ids)")
    suspend fun deletePasses(ids: List<Long>)

    @Query("DELETE FROM passes")
    suspend fun clearPasses()
}
