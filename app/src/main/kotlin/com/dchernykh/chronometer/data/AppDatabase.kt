package com.dchernykh.chronometer.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CutoffEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cutoffDao(): CutoffDao
}
