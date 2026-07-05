package com.dchernykh.chronometer

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
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
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * End-to-end UI smoke tests. They are layout-agnostic on purpose so the same
 * suite runs unchanged on both a phone and a tablet emulator profile
 * (`connectedAndroidTest` against each AVD). Recording is async (Room insert ->
 * Flow -> recomposition), so we wait until the row appears rather than relying on
 * waitForIdle, and check existence (the soft keyboard can cover the list).
 *
 * `Activity.recreate()` is racy on the slower tablet AVD: interacting before the
 * recreated activity's Compose owner is registered lands on the old, detached
 * tree. [recreateAndSettle] resumes the new activity and waits for a single fresh
 * `numberField` before any interaction; [RetryRule] retries an occasional
 * emulator hiccup.
 */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // composeRule is outer so the activity launches once; RetryRule re-runs the
    // @Before/test/@After block (each @Before recreates a fresh activity anyway).
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(composeRule).around(RetryRule(MAX_ATTEMPTS))

    @Before
    fun resetBeforeTest() {
        resetStoredSettings()
        recreateAndSettle()
    }

    @After
    fun resetAfterTest() {
        RaceService.stop(appContext())
        resetStoredSettings()
    }

    /** Recreate the activity and wait until the fresh main screen is interactive. */
    private fun recreateAndSettle() {
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = AWAIT_MS) {
            composeRule.onAllNodesWithTag("numberField").fetchSemanticsNodes().size == 1
        }
    }

    private fun recordAndAwait(
        tag: String,
        number: String,
    ) {
        composeRule.onNodeWithTag("numberField").performTextReplacement(number)
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
        composeRule.onNodeWithText("DSQ").assertExists()
    }

    @Test
    fun opensSettingsScreen() {
        openSettings()
    }

    @Test
    fun languageChoiceChangesSettingsText() {
        openSettings()
        composeRule.onNodeWithTag("langKk").performScrollTo().performClick()

        saveSettings()

        waitForText(localizedString(AppLanguage.KK, R.string.settings))
        openSettings()
        composeRule.onNodeWithTag("langKk").performScrollTo().assertIsSelected()
    }

    @Test
    fun themeChoicePersistsAfterActivityRecreate() {
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
        openSettings()
        setToggle("finishModeCheckbox", enabled = true)

        saveSettings()
        recreateAndSettle()
        recordAndAwait("cutoffButton", "93123")

        composeRule.onNodeWithText(CutoffEvent.FINISH).assertExists()
    }

    @Test
    fun textNumberInputAllowsLettersAfterActivityRecreate() {
        openSettings()
        setToggle("numericInputSwitch", enabled = false)

        saveSettings()
        recreateAndSettle()
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

    /** Retries a test a few times to absorb rare emulator/recreate hiccups. */
    private class RetryRule(
        private val attempts: Int,
    ) : TestRule {
        override fun apply(
            base: Statement,
            description: Description,
        ): Statement =
            object : Statement() {
                override fun evaluate() {
                    var lastError: Throwable? = null
                    repeat(attempts) {
                        try {
                            base.evaluate()
                            return
                        } catch (error: Throwable) {
                            lastError = error
                        }
                    }
                    throw lastError ?: IllegalStateException("no attempts run")
                }
            }
    }

    private companion object {
        // The tablet AVD is markedly slower; give the async record -> Room -> Flow
        // pipeline and post-recreate recompositions generous headroom.
        const val AWAIT_MS = 20_000L
        const val MAX_ATTEMPTS = 3
    }
}
