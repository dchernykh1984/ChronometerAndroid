package com.dchernykh.chronometer

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.ui.ChronometerViewModel
import com.dchernykh.chronometer.ui.MainScreen
import com.dchernykh.chronometer.ui.SettingsScreen
import com.dchernykh.chronometer.ui.theme.ChronometerTheme
import com.dchernykh.chronometer.util.LocaleContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Apply the stored UI language before any resources are resolved.
        val language = SettingsStore(newBase).load().language
        super.attachBaseContext(LocaleContext.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock phones to portrait so the timing UI stays vertical-only. Large
        // screens are left unrestricted: Android ignores orientation locks on
        // large screens from API 36+, and forcing portrait there triggers a
        // Compose relayout crash (BringIntoView before parents are placed).
        if (isPhoneWidth(resources.configuration.smallestScreenWidthDp)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent { AppRoot() }
    }
}

// Below this smallest-width the device is treated as a phone and locked to
// portrait; at or above it (tablets) the orientation is left to the system.
internal const val LARGE_SCREEN_WIDTH_DP = 600

internal fun isPhoneWidth(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp < LARGE_SCREEN_WIDTH_DP

@Composable
private fun AppRoot() {
    val viewModel: ChronometerViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    ChronometerTheme(themeMode = settings.themeMode) {
        var showSettings by rememberSaveable { mutableStateOf(false) }
        if (showSettings) {
            SettingsScreen(viewModel, onBack = { showSettings = false })
        } else {
            MainScreen(viewModel, onOpenSettings = { showSettings = true })
        }
    }
}
