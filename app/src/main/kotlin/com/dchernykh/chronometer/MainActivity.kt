package com.dchernykh.chronometer

import android.content.Context
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
        setContent { AppRoot() }
    }
}

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
