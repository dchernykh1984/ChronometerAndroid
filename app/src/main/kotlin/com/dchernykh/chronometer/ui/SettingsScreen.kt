package com.dchernykh.chronometer.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.AppLanguage
import com.dchernykh.chronometer.data.ThemeMode
import com.dchernykh.chronometer.data.isUploadConfigured
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: ChronometerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Baseline to detect edits; reset after a save or a "New competition".
    var initialSettings by remember { mutableStateOf(viewModel.loadSettings()) }
    var settings by remember { mutableStateOf(initialSettings) }
    var storageGranted by remember { mutableStateOf(StoragePermission.isGranted(context)) }
    var folderRefresh by remember { mutableStateOf(0) }
    val folderSizeBytes by produceState(0L, folderRefresh) { value = viewModel.dataFolderSizeBytes() }
    var confirmNewCompetition by remember { mutableStateOf(false) }
    var newCompetitionFailed by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val manageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            storageGranted = StoragePermission.isGranted(context)
        }
    val writeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            storageGranted = granted
        }

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageLauncher.launch(StoragePermission.manageAllFilesIntent(context))
        } else {
            writeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val sendMisconfigured = settings.sendEnabled && !settings.isUploadConfigured
    val hasUnsavedChanges = settings != initialSettings

    fun saveAndExit() {
        viewModel.saveSettings(settings)
        if (settings.language != initialSettings.language) {
            // Re-run attachBaseContext with the new locale.
            (context as? Activity)?.recreate()
        } else {
            onBack()
        }
    }

    // Going back with pending edits asks to save/discard instead of dropping them.
    fun attemptBack() {
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler { attemptBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    TextButton(onClick = { attemptBack() }, modifier = Modifier.testTag("backButton")) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = settings.siteUrl,
                onValueChange = { settings = settings.copy(siteUrl = it) },
                label = { Text(stringResource(R.string.site_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("siteUrlField"),
            )
            OutlinedTextField(
                value = settings.token,
                onValueChange = { settings = settings.copy(token = it) },
                label = { Text(stringResource(R.string.token)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("tokenField"),
            )
            OutlinedTextField(
                value = settings.pointNumber.toString(),
                onValueChange = {
                    settings = settings.copy(pointNumber = it.filter(Char::isDigit).toIntOrNull() ?: 0)
                },
                label = { Text(stringResource(R.string.point_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("pointField"),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = settings.deviceUuid,
                    onValueChange = { settings = settings.copy(deviceUuid = it) },
                    label = { Text(stringResource(R.string.device_uuid)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("uuidField"),
                )
                TextButton(onClick = {
                    settings = settings.copy(deviceUuid = UUID.randomUUID().toString())
                }) { Text(stringResource(R.string.generate)) }
            }
            OutlinedTextField(
                value = settings.folderPath,
                onValueChange = { settings = settings.copy(folderPath = it) },
                label = { Text(stringResource(R.string.folder_path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("folderField"),
            )

            Text(stringResource(R.string.language))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip(R.string.language_system, "langSystem", settings.language == AppLanguage.SYSTEM) {
                    settings = settings.copy(language = AppLanguage.SYSTEM)
                }
                ChoiceChip(R.string.language_ru, "langRu", settings.language == AppLanguage.RU) {
                    settings = settings.copy(language = AppLanguage.RU)
                }
                ChoiceChip(R.string.language_kk, "langKk", settings.language == AppLanguage.KK) {
                    settings = settings.copy(language = AppLanguage.KK)
                }
                ChoiceChip(R.string.language_en, "langEn", settings.language == AppLanguage.EN) {
                    settings = settings.copy(language = AppLanguage.EN)
                }
            }

            Text(stringResource(R.string.theme))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip(R.string.theme_system, "themeSystem", settings.themeMode == ThemeMode.SYSTEM) {
                    settings = settings.copy(themeMode = ThemeMode.SYSTEM)
                }
                ChoiceChip(R.string.theme_light, "themeLight", settings.themeMode == ThemeMode.LIGHT) {
                    settings = settings.copy(themeMode = ThemeMode.LIGHT)
                }
                ChoiceChip(R.string.theme_dark, "themeDark", settings.themeMode == ThemeMode.DARK) {
                    settings = settings.copy(themeMode = ThemeMode.DARK)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(
                        if (settings.numericInput) R.string.number_input_digits else R.string.number_input_text,
                    ),
                )
                Switch(
                    checked = settings.numericInput,
                    onCheckedChange = { settings = settings.copy(numericInput = it) },
                    modifier = Modifier.testTag("numericInputSwitch"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = settings.finishMode,
                    onCheckedChange = { settings = settings.copy(finishMode = it) },
                    modifier = Modifier.testTag("finishModeCheckbox"),
                )
                // finish/nextLap are protocol tokens: kept in English in every locale.
                Text(stringResource(R.string.finish_mode))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.send_enabled))
                Switch(
                    checked = settings.sendEnabled,
                    onCheckedChange = { settings = settings.copy(sendEnabled = it) },
                    modifier = Modifier.testTag("sendSwitch"),
                )
            }

            if (sendMisconfigured) {
                Text(
                    text = stringResource(R.string.send_requires_url_token),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("sendError"),
                )
            }

            if (!storageGranted) {
                Text(
                    text = stringResource(R.string.storage_permission_needed),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = ::requestStorage, modifier = Modifier.testTag("grantButton")) {
                    Text(stringResource(R.string.grant_storage))
                }
            }

            Text(
                text = stringResource(R.string.data_folder_size, formatBytes(folderSizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("folderSize"),
            )
            OutlinedButton(
                onClick = { confirmNewCompetition = true },
                modifier = Modifier.fillMaxWidth().testTag("newCompetitionButton"),
            ) { Text(stringResource(R.string.new_competition)) }
            if (newCompetitionFailed) {
                Text(
                    text = stringResource(R.string.new_competition_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("newCompetitionError"),
                )
            }

            Button(
                onClick = { saveAndExit() },
                enabled = !sendMisconfigured,
                modifier = Modifier.fillMaxWidth().testTag("saveButton"),
            ) { Text(stringResource(R.string.save)) }
        }

        SettingsDialogs(
            confirmNewCompetition = confirmNewCompetition,
            showUnsavedDialog = showUnsavedDialog,
            saveEnabled = !sendMisconfigured,
            onConfirmNewCompetition = {
                confirmNewCompetition = false
                viewModel.startNewCompetition { success ->
                    newCompetitionFailed = !success
                    if (success) {
                        // Persisted already: reset the baseline so a later back
                        // press does not re-prompt to save.
                        settings = viewModel.loadSettings()
                        initialSettings = settings
                        folderRefresh++
                    }
                }
            },
            onDismissNewCompetition = { confirmNewCompetition = false },
            onSaveChanges = {
                showUnsavedDialog = false
                saveAndExit()
            },
            onDiscardChanges = {
                showUnsavedDialog = false
                onBack()
            },
            onDismissUnsaved = { showUnsavedDialog = false },
        )
    }
}

@Composable
private fun SettingsDialogs(
    confirmNewCompetition: Boolean,
    showUnsavedDialog: Boolean,
    saveEnabled: Boolean,
    onConfirmNewCompetition: () -> Unit,
    onDismissNewCompetition: () -> Unit,
    onSaveChanges: () -> Unit,
    onDiscardChanges: () -> Unit,
    onDismissUnsaved: () -> Unit,
) {
    if (confirmNewCompetition) {
        AlertDialog(
            onDismissRequest = onDismissNewCompetition,
            title = { Text(stringResource(R.string.new_competition)) },
            text = { Text(stringResource(R.string.new_competition_confirm)) },
            confirmButton = {
                TextButton(onClick = onConfirmNewCompetition, modifier = Modifier.testTag("confirmNewCompetition")) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissNewCompetition) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = onDismissUnsaved,
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = onSaveChanges,
                    enabled = saveEnabled,
                    modifier = Modifier.testTag("saveChangesButton"),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = onDiscardChanges, modifier = Modifier.testTag("discardChangesButton")) {
                    Text(stringResource(R.string.discard))
                }
            },
            modifier = Modifier.testTag("unsavedChangesDialog"),
        )
    }
}

/** Human-readable folder size for the settings info line. */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

@Composable
private fun ChoiceChip(
    @StringRes labelRes: Int,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes), maxLines = 1) },
        modifier = Modifier.testTag(tag),
    )
}
