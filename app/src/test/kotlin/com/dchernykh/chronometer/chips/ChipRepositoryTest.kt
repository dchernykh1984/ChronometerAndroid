package com.dchernykh.chronometer.chips

import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChipRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var store: SettingsStore
    private lateinit var folder: File

    @Before
    fun setUp() {
        store = SettingsStore(context)
        folder = File(context.cacheDir, "chips-${System.nanoTime()}").apply { mkdirs() }
        store.save(store.load().copy(folderPath = folder.path))
    }

    private fun startTxt(vararg chipFields: String) =
        File(folder, "start.txt").writeText(
            chipFields.withIndex().joinToString(
                "",
            ) { (i, chips) -> "${15 + i}#R#M#3#1#1990#T#C##0 0:0:0.000#r#$chips#\n" },
        )

    @Test
    fun startTxtIsReadOnceAtStartUpAndAgainOnReload() =
        runTest {
            val chips = ChipRepository(store)
            assertNull(chips.protocol.value)
            startTxt("A1,A2")
            chips.ensureMap()
            assertEquals(
                mapOf("A1" to "15", "A2" to "15"),
                chips.protocol.value!!
                    .map.bibOf,
            )
            startTxt("A1", "B1")
            chips.ensureMap()
            assertEquals(
                1,
                chips.protocol.value!!
                    .map.bibs,
            )
            chips.reloadMap()
            assertEquals(
                2,
                chips.protocol.value!!
                    .map.bibs,
            )
        }

    @Test
    fun withoutStartTxtTheMapIsEmptyAndSaysSo() =
        runTest {
            val chips = ChipRepository(store)
            chips.reloadMap()
            assertEquals(StartProtocol.Missing, chips.protocol.value)
            assertTrue(
                chips.protocol.value!!
                    .map.bibOf
                    .isEmpty(),
            )
        }

    @Test
    fun aStartTxtThatCannotBeReadIsToldFromAMissingOne() =
        runTest {
            File(folder, "start.txt").writeText("15#")
            File(folder, "start.txt").setReadable(false)
            val chips = ChipRepository(store)
            chips.reloadMap()
            val read = chips.protocol.value
            if (File(folder, "start.txt").canRead()) {
                return@runTest // running as root reads every file
            }
            assertTrue(read.toString(), read is StartProtocol.Unreadable)
            assertTrue(read!!.map.bibOf.isEmpty())
        }
}
