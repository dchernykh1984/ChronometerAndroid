package com.dchernykh.chronometer.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CutoffEntity::class,
        ChipReadEntity::class,
        PassEntity::class,
        AttributedChipEntity::class,
        PendingBreakEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cutoffDao(): CutoffDao

    abstract fun chipDao(): ChipDao

    abstract fun passDao(): PassDao

    abstract fun beamDao(): BeamDao
}
