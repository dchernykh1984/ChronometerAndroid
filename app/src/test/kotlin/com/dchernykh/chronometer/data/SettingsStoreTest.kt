package com.dchernykh.chronometer.data

import android.content.Context
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
    fun newCompetitionClearsTokenAndPointAndKeepsRest() {
        store.save(
            store.load().copy(
                siteUrl = "http://s",
                token = "tok",
                pointNumber = 5,
                numericInput = false,
            ),
        )

        store.resetForNewCompetition()

        val loaded = store.load()
        assertEquals("", loaded.token)
        assertEquals(0, loaded.pointNumber)
        // Unrelated preferences are preserved.
        assertEquals("http://s", loaded.siteUrl)
        assertFalse(loaded.numericInput)
    }

    @Test
    fun languageDefaultsToSystem() {
        assertEquals(AppLanguage.SYSTEM, store.load().language)
    }

    @Test
    fun persistsLanguageAcrossRestart() {
        store.save(store.load().copy(language = AppLanguage.KK))
        assertEquals(AppLanguage.KK, SettingsStore(RuntimeEnvironment.getApplication()).load().language)
    }

    @Test
    fun clientRevisionIncrementsAndPersists() {
        val start = store.clientRevision()
        assertEquals(start + 1, store.nextClientRevision())
        assertEquals(start + 2, store.nextClientRevision())
        assertEquals(start + 2, store.clientRevision())
    }

    @Test
    fun revisionStoredAsIntByAnOlderVersionCarriesOn() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("chronometer_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("client_revision", 41)
            .commit()

        assertEquals(41L, store.clientRevision())
        assertEquals(42L, store.nextClientRevision())
        assertEquals(42L, SettingsStore(RuntimeEnvironment.getApplication()).clientRevision())
    }

    @Test
    fun revisionCountsPastTheIntRange() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("chronometer_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("client_revision", Int.MAX_VALUE)
            .commit()

        assertEquals(Int.MAX_VALUE.toLong() + 1, store.nextClientRevision())
    }

    @Test
    fun newCompetitionKeepsCountingRevisions() {
        // The same competition's token may be entered again after a reset: a counter
        // restarted from 1 would sit below the revision the server already holds for
        // this device and every upload would be refused with HTTP 409.
        store.nextClientRevision()
        val beforeReset = store.nextClientRevision()

        store.resetForNewCompetition()

        assertEquals(beforeReset, store.clientRevision())
        assertEquals(beforeReset + 1, store.nextClientRevision())
    }
}
