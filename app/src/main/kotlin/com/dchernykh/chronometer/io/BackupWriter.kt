package com.dchernykh.chronometer.io

import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes the timing files into the user-configured folder (all-files access):
 *  - `<folder>/results.txt`        current list, the input for existing tools;
 *  - `<folder>/backup/<stamp>.txt` an immutable per-press snapshot.
 *
 * Each file is written to a temp file (fsynced) and then atomically replaced, so
 * a crash mid-write cannot leave a partially written file. Failures never throw
 * out of [writeSnapshot]: the cutoff is already durable in Room, so file
 * problems must not disrupt timing - they are reported via the return value.
 */
class BackupWriter {
    fun writeSnapshot(
        folderPath: String,
        items: List<String>,
        stamp: Long,
    ): Boolean =
        runCatching {
            val folder = File(folderPath)
            folder.mkdirs()
            val body = if (items.isEmpty()) "" else items.joinToString(separator = "\n", postfix = "\n")
            writeAtomically(File(folder, "results.txt"), body.toByteArray(Charsets.UTF_8))
            val backupDir = File(folder, "backup").apply { mkdirs() }
            // Never overwrite an existing snapshot (e.g. two presses on the same
            // stamp): pick a free "<stamp>.txt" / "<stamp>-N.txt" name instead.
            writeAtomically(freeBackupFile(backupDir, "$stamp"), body.toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false)

    /**
     * Write [body] over [target] in one step, after copying what it held, byte for byte, to
     * `backup/<name>-<stamp>.txt` under a free name, so an earlier copy is never overwritten.
     * False, with [target] untouched, when either cannot be written.
     */
    fun replaceKeepingBackup(
        target: File,
        body: String,
        stamp: Long,
    ): Boolean =
        runCatching {
            val folder = checkNotNull(target.parentFile).apply { mkdirs() }
            if (target.isFile) {
                val backupDir = File(folder, "backup").apply { mkdirs() }
                writeAtomically(freeBackupFile(backupDir, "${target.nameWithoutExtension}-$stamp"), target.readBytes())
            }
            writeAtomically(target, body.toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false)

    private fun freeBackupFile(
        backupDir: File,
        name: String,
    ): File {
        val base = File(backupDir, "$name.txt")
        if (!base.exists()) {
            return base
        }
        var suffix = 1
        while (File(backupDir, "$name-$suffix.txt").exists()) {
            suffix++
        }
        return File(backupDir, "$name-$suffix.txt")
    }

    /**
     * Reset `results.txt` to empty for a new competition. The `backup/` snapshots
     * are intentionally left untouched. Returns false if the file cannot be written.
     */
    fun resetResults(folderPath: String): Boolean =
        runCatching {
            val folder = File(folderPath)
            folder.mkdirs()
            writeAtomically(File(folder, "results.txt"), ByteArray(0))
            true
        }.getOrDefault(false)

    /**
     * Append lines to a raw device file in the data folder ([RawFiles]). False, and
     * nothing thrown, when it cannot be written: what it holds is in Room already.
     */
    fun appendRaw(
        folderPath: String,
        name: String,
        lines: List<String>,
    ): Boolean =
        runCatching {
            if (lines.isNotEmpty()) {
                val folder = File(folderPath).apply { mkdirs() }
                File(folder, name).appendText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            }
            true
        }.getOrDefault(false)

    /** Total size in bytes of the data folder (results + all backups), 0 if missing. */
    fun folderSizeBytes(folderPath: String): Long {
        val folder = File(folderPath)
        if (!folder.exists()) {
            return 0
        }
        return folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun writeAtomically(
        target: File,
        body: ByteArray,
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(body)
            out.flush()
            out.fd.sync()
        }
        if (!atomicReplace(tmp, target)) {
            // Could not replace atomically: leave the previous file untouched and
            // report the failure instead of a partial direct overwrite.
            tmp.delete()
            throw IOException("atomic replace failed for ${target.name}")
        }
    }

    private fun atomicReplace(
        tmp: File,
        target: File,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
        } else {
            // File.renameTo maps to POSIX rename(2) on Android: an atomic replace.
            tmp.renameTo(target)
        }
}
