package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val store = SettingsStore(RuntimeEnvironment.getApplication())

    @Test
    fun generatesDeviceUuidOnFirstLoad() {
        assertTrue(store.load().deviceUuid.isNotBlank())
    }

    @Test
    fun deviceUuidIsStableAcrossLoads() {
        assertEquals(store.load().deviceUuid, store.load().deviceUuid)
    }

    @Test
    fun savesAndReloadsSettings() {
        val uuid = store.load().deviceUuid
        store.save(
            Settings(
                siteUrl = "http://site",
                token = "tok",
                pointNumber = 3,
                deviceUuid = uuid,
                folderPath = "/folder",
                sendEnabled = true,
            ),
        )
        val loaded = store.load()
        assertEquals("http://site", loaded.siteUrl)
        assertEquals("tok", loaded.token)
        assertEquals(3, loaded.pointNumber)
        assertEquals("/folder", loaded.folderPath)
        assertTrue(loaded.sendEnabled)
    }

    @Test
    fun numericInputDefaultsToTrue() {
        assertTrue(store.load().numericInput)
    }

    @Test
    fun persistsNumericInputAcrossRestart() {
        store.save(store.load().copy(numericInput = false))
        // A fresh store reads the same SharedPreferences, i.e. survives a restart.
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).load().numericInput)
    }

    @Test
    fun finishModeDefaultsToFalse() {
        assertFalse(store.load().finishMode)
    }

    @Test
    fun persistsFinishModeAcrossRestart() {
        store.save(store.load().copy(finishMode = true))
        assertTrue(SettingsStore(RuntimeEnvironment.getApplication()).load().finishMode)
    }

    @Test
    fun themeModeDefaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, store.load().themeMode)
    }

    @Test
    fun persistsThemeModeAcrossRestart() {
        store.save(store.load().copy(themeMode = ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, SettingsStore(RuntimeEnvironment.getApplication()).load().themeMode)
    }

    @Test
    fun newCompetitionClearsTokenPointRevisionAndKeepsRest() {
        store.save(
            store.load().copy(
                siteUrl = "http://s",
                token = "tok",
                pointNumber = 5,
                numericInput = false,
            ),
        )
        store.nextClientRevision()

        store.resetForNewCompetition()

        val loaded = store.load()
        assertEquals("", loaded.token)
        assertEquals(0, loaded.pointNumber)
        assertEquals(0, store.clientRevision())
        // Unrelated preferences are preserved.
        assertEquals("http://s", loaded.siteUrl)
        assertFalse(loaded.numericInput)
    }

    @Test
    fun clientRevisionIncrementsAndPersists() {
        val start = store.clientRevision()
        assertEquals(start + 1, store.nextClientRevision())
        assertEquals(start + 2, store.nextClientRevision())
        assertEquals(start + 2, store.clientRevision())
    }
}
