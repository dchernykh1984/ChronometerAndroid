package com.dchernykh.chronometer.startprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

// Cyrillic names, escaped: files here stay ASCII.
private const val IVANOV = "\u0418\u0432\u0430\u043d\u043e\u0432"
private const val PETROV = "\u041f\u0435\u0442\u0440\u043e\u0432"

class StartProtocolFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cp1251 = charset("windows-1251")

    @Test
    fun withoutTheFileThereAreNoLines() {
        assertNull(StartProtocolFile.read(tmp.root.path))
        tmp.newFolder(StartProtocolFile.NAME)
        assertNull(StartProtocolFile.read(tmp.root.path))
    }

    @Test
    fun eachLineIsUtf8OrElseCp1251AndBlankLinesAreDropped() {
        val bytes =
            "15#$IVANOV#\r\n".toByteArray() + "\n  \n".toByteArray() + "16#$PETROV#\n".toByteArray(cp1251) +
                "17#a\u2028b#".toByteArray()
        tmp.newFile(StartProtocolFile.NAME).writeBytes(bytes)
        assertEquals(listOf("15#$IVANOV#", "16#$PETROV#", "17#a", "b#"), StartProtocolFile.read(tmp.root.path))
    }

    @Test
    fun aByteOrderMarkIsNoPartOfTheFirstBib() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(listOf("15#A#", "16#B#"), StartProtocolFile.lines(bom + "15#A#\n16#B#\n".toByteArray()))
        // A first line in cp1251 loses it too.
        assertEquals(listOf("15#$IVANOV#"), StartProtocolFile.lines(bom + "15#$IVANOV#\n".toByteArray(cp1251)))
    }

    @Test
    fun aByteNeitherUtf8NorCp1251IsReadAsLatin1() {
        assertEquals(listOf("x\u0098"), StartProtocolFile.lines(byteArrayOf('x'.code.toByte(), 0x98.toByte())))
    }

    @Test(expected = IOException::class)
    fun aFileThatCannotBeReadSaysWhy() {
        val file = tmp.newFile(StartProtocolFile.NAME)
        file.writeText("15#")
        file.setReadable(false)
        assumeFalse("running as root reads every file", file.canRead())
        StartProtocolFile.read(tmp.root.path)
    }
}
