package com.dchernykh.chronometer.io

import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes the timing files into the user-configured folder (all-files access):
 *  - `<folder>/results.txt`         current list, the input for existing tools;
 *  - `<folder>/backup/<millis>.txt` an immutable per-press snapshot.
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
        stampMs: Long,
    ): Boolean =
        runCatching {
            val folder = File(folderPath)
            folder.mkdirs()
            val body = if (items.isEmpty()) "" else items.joinToString(separator = "\n", postfix = "\n")
            writeAtomically(File(folder, "results.txt"), body)
            val backupDir = File(folder, "backup").apply { mkdirs() }
            writeAtomically(File(backupDir, "$stampMs.txt"), body)
            true
        }.getOrDefault(false)

    /**
     * Reset `results.txt` to empty for a new competition. The `backup/` snapshots
     * are intentionally left untouched. Returns false if the file cannot be written.
     */
    fun resetResults(folderPath: String): Boolean =
        runCatching {
            val folder = File(folderPath)
            folder.mkdirs()
            writeAtomically(File(folder, "results.txt"), "")
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
        body: String,
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
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
