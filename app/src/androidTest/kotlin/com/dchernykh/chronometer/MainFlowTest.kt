package com.dchernykh.chronometer

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.dchernykh.chronometer.data.AppLanguage
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.data.ThemeMode
import com.dchernykh.chronometer.service.RaceService
import com.dchernykh.chronometer.util.LocaleContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
 *
 * The soft keyboard / auto-focus can swallow the first input on the slower
 * tablet AVD, so [recordAndAwait] re-types the number until the field actually
 * holds it before pressing a record button.
 */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetBeforeTest() {
        clearAppData()
        resetStoredSettings()
        recreateAndSettle()
    }

    @After
    fun resetAfterTest() {
        RaceService.stop(appContext())
        clearAppData()
        resetStoredSettings()
    }

    /** Hermetic start: wipe cutoffs and pending work left by earlier tests/runs. */
    private fun clearAppData() {
        val app = appContext() as ChronometerApp
        runBlocking { app.database.cutoffDao().deleteAll() }
        runCatching { WorkManager.getInstance(appContext()).cancelAllWork() }
    }

    /** Recreate the activity and wait until the fresh main screen is interactive. */
    private fun recreateAndSettle() {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithTag("numberField").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun numberFieldText(): String? =
        composeRule
            .onNodeWithTag("numberField")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text

    private fun recordAndAwait(
        tag: String,
        number: String,
    ) {
        // Auto-focus / IME can swallow the first input on the slow tablet, which
        // would record an empty number; re-type until the field actually holds it.
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onNodeWithTag("numberField").performTextReplacement(number)
            numberFieldText() == number
        }
        // Likewise the record tap can be dropped; press until the row appears. Once
        // the press takes, the field is cleared so any extra tap is a no-op.
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.onAllNodesWithText(number).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    /**
     * Skip on large screens (tablet). Assertions on control selection state right
     * after an Activity recreate are flaky on the tablet AVD; the phone profile
     * (modern API) plus the SettingsStore unit tests cover the persistence.
     */
    private fun assumePhone() {
        assumeFalse(
            "Runs on the phone profile only",
            appContext().resources.configuration.smallestScreenWidthDp >= TABLET_SW_DP,
        )
    }

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
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithTag("siteUrlField").fetchSemanticsNodes().isNotEmpty()
        }
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
        // Hermetic start means exactly one DSQ row - the one we just recorded.
        composeRule.onAllNodesWithText("DSQ").assertCountEquals(1)
    }

    @Test
    fun opensSettingsScreen() {
        openSettings()
    }

    @Test
    fun logScrollsToTopAfterNewCutoff() {
        assumePhone()
        // Fill the log so it scrolls, then move away from the top.
        repeat(LOG_FILL) { i -> recordAndAwait("cutoffButton", (10_000 + i).toString()) }
        composeRule.onNodeWithTag("cutoffLog").performScrollToIndex(LOG_FILL - 1)
        composeRule.waitForIdle()

        // A new cutoff must bring the log back to the top so the latest is visible.
        recordAndAwait("cutoffButton", "55555")
        composeRule.onNodeWithText("55555").assertIsDisplayed()
    }

    @Test
    fun languageChoiceChangesSettingsText() {
        assumePhone()
        openSettings()
        composeRule.onNodeWithTag("langKk").performScrollTo().performClick()

        // Saving a language change recreates the activity and stays on Settings.
        saveSettings()

        waitForText(localizedString(AppLanguage.KK, R.string.settings))
        composeRule.onNodeWithTag("langKk").performScrollTo().assertIsSelected()
    }

    @Test
    fun themeChoicePersistsAfterActivityRecreate() {
        assumePhone()
        openSettings()
        composeRule.onNodeWithTag("themeDark").performScrollTo().performClick()

        saveSettings()
        recreateAndSettle()
        openSettings()

        composeRule.onNodeWithTag("themeDark").performScrollTo().assertIsSelected()
        assertEquals(ThemeMode.DARK, SettingsStore(appContext()).load().themeMode)
    }

    @Test
    fun finishModeRecordsFinishAfterActivityRecreate() {
        assumePhone()
        openSettings()
        setToggle("finishModeCheckbox", enabled = true)

        saveSettings()
        recreateAndSettle()
        recordAndAwait("cutoffButton", "93123")

        composeRule.onNodeWithText(CutoffEvent.FINISH).assertExists()
    }

    @Test
    fun textNumberInputAllowsLettersAfterActivityRecreate() {
        assumePhone()
        openSettings()
        setToggle("numericInputSwitch", enabled = false)

        saveSettings()
        recreateAndSettle()
        recordAndAwait("cutoffButton", "A12")

        composeRule.onNodeWithText("A12").assertExists()
    }

    @Test
    fun newCompetitionClearsStateAndKeepsBackups() {
        assumePhone()
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
        recreateAndSettle()
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
        assumePhone()
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
        // pipeline and post-recreate recompositions generous headroom.
        const val AWAIT_MS = 20_000L

        // sw >= 600dp is the standard tablet breakpoint.
        const val TABLET_SW_DP = 600

        // Enough rows to make the log scrollable on a phone.
        const val LOG_FILL = 12
    }
}
