package com.dchernykh.chronometer

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dchernykh.chronometer.data.AppLanguage
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.ThemeMode
import com.dchernykh.chronometer.service.RaceService
import com.dchernykh.chronometer.util.LocaleContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end UI smoke tests. They are layout-agnostic on purpose so the same
 * suite runs unchanged on both a phone and a tablet emulator profile
 * (`connectedAndroidTest` against each AVD). Recording is async (Room insert ->
 * Flow -> recomposition), so we wait until the row appears rather than relying on
 * waitForIdle, and check existence (the soft keyboard can cover the list).
 */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetBeforeTest() {
        resetStoredSettings()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun resetAfterTest() {
        RaceService.stop(appContext())
        resetStoredSettings()
    }

    private fun recordAndAwait(
        tag: String,
        number: String,
    ) {
        composeRule.onNodeWithTag("numberField").performTextInput(number)
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithText(number).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private fun resetStoredSettings() {
        val store = SettingsStore(appContext())
        store.save(
            store.load().copy(
                token = "",
                pointNumber = 0,
                sendEnabled = false,
                numericInput = true,
                finishMode = false,
                themeMode = ThemeMode.SYSTEM,
                language = AppLanguage.EN,
            ),
        )
    }

    private fun openSettings() {
        composeRule.onNodeWithTag("settingsButton").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("siteUrlField").assertIsDisplayed()
    }

    private fun saveSettings() {
        composeRule.onNodeWithTag("saveButton").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun replaceSettingsText(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    }

    private fun setToggle(
        tag: String,
        enabled: Boolean,
    ) {
        val node = composeRule.onNodeWithTag(tag).performScrollTo()
        val current = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        if ((current == ToggleableState.On) != enabled) {
            node.performClick()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun localizedString(
        language: AppLanguage,
        @StringRes stringRes: Int,
    ): String = LocaleContext.wrap(appContext(), language).getString(stringRes)

    @Test
    fun recordingCutoffShowsItInLog() {
        recordAndAwait("cutoffButton", "70123")
        composeRule.onNodeWithText("70123").assertExists()
    }

    @Test
    fun disqualificationShowsMarker() {
        recordAndAwait("dsqButton", "80123")
        composeRule.onNodeWithText("80123").assertExists()
        composeRule.onNodeWithText("DSQ").assertExists()
    }

    @Test
    fun opensSettingsScreen() {
        composeRule.onNodeWithTag("settingsButton").performClick()
        composeRule.waitForIdle()
        // The URL field is at the top of the settings screen, always on-screen.
        composeRule.onNodeWithTag("siteUrlField").assertIsDisplayed()
    }

    @Test
    fun languageChoiceChangesSettingsText() {
        openSettings()
        composeRule.onNodeWithTag("langKk").performScrollTo().performClick()

        saveSettings()

        waitForText(localizedString(AppLanguage.KK, R.string.settings))
        composeRule.onNodeWithTag("langKk").performScrollTo().assertIsSelected()
    }

    @Test
    fun themeChoicePersistsAfterActivityRecreate() {
        openSettings()
        composeRule.onNodeWithTag("themeDark").performScrollTo().performClick()

        saveSettings()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        openSettings()

        composeRule.onNodeWithTag("themeDark").performScrollTo().assertIsSelected()
        assertEquals(ThemeMode.DARK, SettingsStore(appContext()).load().themeMode)
    }

    @Test
    fun finishModeRecordsFinishAfterActivityRecreate() {
        openSettings()
        setToggle("finishModeCheckbox", enabled = true)

        saveSettings()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        recordAndAwait("cutoffButton", "93123")

        composeRule.onNodeWithText(CutoffEvent.FINISH).assertExists()
    }

    @Test
    fun textNumberInputAllowsLettersAfterActivityRecreate() {
        openSettings()
        setToggle("numericInputSwitch", enabled = false)

        saveSettings()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        recordAndAwait("cutoffButton", "A12")

        composeRule.onNodeWithText("A12").assertExists()
    }

    @Test
    fun newCompetitionClearsStateAndKeepsBackups() {
        val context = appContext()
        val number = "99123"
        val folder = File(context.cacheDir, "ui-competition-${System.nanoTime()}").apply { mkdirs() }
        val resultsFile = File(folder, "results.txt")
        val backupDir = File(folder, "backup")

        openSettings()
        replaceSettingsText("folderField", folder.absolutePath)
        replaceSettingsText("tokenField", "ui-token")
        replaceSettingsText("pointField", "7")
        saveSettings()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        recordAndAwait("cutoffButton", number)
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            backupDir.listFiles()?.isNotEmpty() == true
        }

        openSettings()
        composeRule.onNodeWithTag("newCompetitionButton").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirmNewCompetition").performClick()
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            val settings = SettingsStore(context).load()
            settings.token.isEmpty() &&
                settings.pointNumber == 0 &&
                resultsFile.exists() &&
                resultsFile.readText() == ""
        }

        assertEquals("", resultsFile.readText())
        assertTrue(backupDir.listFiles()?.isNotEmpty() == true)
        composeRule.onNodeWithTag("backButton").performClick()
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithText(number).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun recordingWorksWhileEventServiceActive() {
        // Start the foreground service directly (no permission dialog), then record.
        val context = ApplicationProvider.getApplicationContext<Context>()
        RaceService.start(context)
        try {
            recordAndAwait("cutoffButton", "90123")
            composeRule.onNodeWithText("90123").assertExists()
        } finally {
            RaceService.stop(context)
        }
    }

    private companion object {
        // The tablet AVD is markedly slower; give the async record -> Room -> Flow
        // pipeline and post-recreate recompositions generous headroom so waits do
        // not time out (the same suite passes comfortably on the phone profile).
        const val AWAIT_MS = 30_000L
    }
}
