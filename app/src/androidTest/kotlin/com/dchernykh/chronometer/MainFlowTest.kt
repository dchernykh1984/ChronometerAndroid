package com.dchernykh.chronometer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * (`connectedAndroidTest` against each AVD).
 */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun recordingCutoffShowsItInLog() {
        val number = "70123"
        composeRule.onNodeWithTag("numberField").performTextInput(number)
        composeRule.onNodeWithTag("cutoffButton").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(number).assertIsDisplayed()
    }

    @Test
    fun disqualificationShowsMarker() {
        val number = "80123"
        composeRule.onNodeWithTag("numberField").performTextInput(number)
        composeRule.onNodeWithTag("dsqButton").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(number).assertIsDisplayed()
        composeRule.onNodeWithText("DSQ").assertIsDisplayed()
    }

    @Test
    fun opensSettingsScreen() {
        composeRule.onNodeWithTag("settingsButton").performClick()
        composeRule.onNodeWithTag("saveButton").assertIsDisplayed()
    }

    @Test
    fun recordingWorksWhileEventServiceActive() {
        // Start the foreground service directly (no permission dialog), then record.
        val context = ApplicationProvider.getApplicationContext<Context>()
        RaceService.start(context)
        try {
            val number = "90123"
            composeRule.onNodeWithTag("numberField").performTextInput(number)
            composeRule.onNodeWithTag("cutoffButton").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(number).assertIsDisplayed()
        } finally {
            RaceService.stop(context)
        }
    }
}
