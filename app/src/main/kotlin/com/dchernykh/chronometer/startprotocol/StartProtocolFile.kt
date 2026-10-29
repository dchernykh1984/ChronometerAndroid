package com.dchernykh.chronometer.startprotocol

import com.dchernykh.chronometer.io.RawFiles
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/** `start.txt` in the data folder: the start protocol the chip map is built from. */
object StartProtocolFile {
    const val NAME = "start.txt"

    private val ENCODINGS = listOf(Charsets.UTF_8, Charset.forName("windows-1251"))

    /**
     * Its non-blank lines, or null when there is no such file. Throws an IOException when
     * the file is there but cannot be read.
     */
    fun read(folderPath: String): List<String>? {
        val file = File(folderPath, NAME)
        return if (file.isFile) lines(file.readBytes()) else null
    }

    /**
     * Each line decoded on its own, as UTF-8, else cp1251, else Latin-1, and split at every
     * line break, as ChronometerPython reads a file the older C++ chronometer began.
     */
    internal fun lines(bytes: ByteArray): List<String> {
        val raw = mutableListOf<ByteArray>()
        var start = 0
        for (i in 0..bytes.size) {
            if (i == bytes.size || bytes[i] == '\n'.code.toByte()) {
                raw += bytes.copyOfRange(start, i)
                start = i + 1
            }
        }
        return raw.flatMap { decode(it).split(RawFiles.LINE_BREAKS) }.filter(String::isNotBlank)
    }

    private fun decode(raw: ByteArray): String =
        ENCODINGS.firstNotNullOfOrNull { charset ->
            try {
                charset.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        } ?: String(raw, Charsets.ISO_8859_1)
}
