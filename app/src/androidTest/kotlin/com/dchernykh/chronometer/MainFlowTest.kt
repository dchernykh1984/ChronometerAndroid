package com.dchernykh.chronometer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dchernykh.chronometer.service.RaceService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private fun recordAndAwait(
        tag: String,
        number: String,
    ) {
        composeRule.onNodeWithTag("numberField").performTextInput(number)
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(number).fetchSemanticsNodes().isNotEmpty()
        }
    }

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
}
