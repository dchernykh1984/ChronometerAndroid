package com.dchernykh.chronometer.io

import java.io.File

/**
 * Writes the timing files into the user-configured folder (all-files access):
 *  - `<folder>/results.txt`         current list, the input for existing tools;
 *  - `<folder>/backup/<millis>.txt` an immutable per-press snapshot.
 *
 * Both are written atomically (temp file + rename, fsync) so a crash mid-write
 * cannot corrupt them. Failures never throw: the cutoff is already durable in
 * Room, so file problems must not disrupt timing.
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
        if (!tmp.renameTo(target)) {
            target.writeText(body, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
