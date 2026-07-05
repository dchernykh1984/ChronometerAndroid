package com.dchernykh.chronometer.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 stored `disqualified INTEGER`; v2 replaces it with `event TEXT`. Recorded
 * cutoffs are preserved: a disqualified row becomes `DSQ`, everything else
 * becomes `nextLap`. The new table matches Room's generated v2 schema exactly.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE `cutoffs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`number` TEXT NOT NULL, `timeStr` TEXT NOT NULL, `event` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `cutoffs_new` (`id`, `number`, `timeStr`, `event`, `createdAt`) " +
                    "SELECT `id`, `number`, `timeStr`, " +
                    "CASE WHEN `disqualified` = 1 THEN 'DSQ' ELSE 'nextLap' END, `createdAt` " +
                    "FROM `cutoffs`",
            )
            db.execSQL("DROP TABLE `cutoffs`")
            db.execSQL("ALTER TABLE `cutoffs_new` RENAME TO `cutoffs`")
        }
    }
