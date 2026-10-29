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

/**
 * v3 adds what the cutoff sources keep: chip reads, passes, the chips whose reads are in
 * passes, and beam breaks waiting for a number. `cutoffs` is untouched. The statements
 * match Room's generated v3 schema exactly.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chip_reads` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chip` TEXT NOT NULL, " +
                    "`timeStr` TEXT NOT NULL, `timeMs` INTEGER NOT NULL, " +
                    "`deviceTime` TEXT NOT NULL, `antenna` TEXT NOT NULL, " +
                    "`reader` TEXT NOT NULL, `rssi` REAL, `bib` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chip_reads_bib_chip` ON `chip_reads` (`bib`, `chip`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `passes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bib` TEXT NOT NULL, " +
                    "`firstMs` INTEGER NOT NULL, `lastMs` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_passes_bib` ON `passes` (`bib`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_breaks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seq` INTEGER NOT NULL, " +
                    "`timeStr` TEXT NOT NULL, `deviceTime` TEXT NOT NULL)",
            )
        }
    }
