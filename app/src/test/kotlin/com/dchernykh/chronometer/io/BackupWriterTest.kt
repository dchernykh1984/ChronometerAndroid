package com.dchernykh.chronometer.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val writer = BackupWriter()

    @Test
    fun writesResultsFileWithEveryItem() {
        val folder = temp.newFolder("data")
        val ok = writer.writeSnapshot(folder.path, listOf("1#t#nextLap#", "2#t#DSQ#"), 111)
        assertTrue(ok)
        assertEquals("1#t#nextLap#\n2#t#DSQ#\n", File(folder, "results.txt").readText())
    }

    @Test
    fun writesBackupSnapshotPerPress() {
        val folder = temp.newFolder("data")
        writer.writeSnapshot(folder.path, listOf("1#t#nextLap#"), 222)
        assertEquals("1#t#nextLap#\n", File(folder, "backup/222.txt").readText())
    }

    @Test
    fun leavesNoTempFileBehind() {
        val folder = temp.newFolder("data")
        writer.writeSnapshot(folder.path, listOf("x"), 1)
        assertFalse(File(folder, "results.txt.tmp").exists())
    }

    @Test
    fun emptyListWritesEmptyFile() {
        val folder = temp.newFolder("data")
        assertTrue(writer.writeSnapshot(folder.path, emptyList(), 1))
        assertEquals("", File(folder, "results.txt").readText())
    }

    @Test
    fun inaccessibleFolderReturnsFalse() {
        // The parent is a regular file, so the folder cannot be created.
        val notADir = temp.newFile("blocker")
        val ok = writer.writeSnapshot(File(notADir, "sub").path, listOf("x"), 1)
        assertFalse(ok)
    }
}
