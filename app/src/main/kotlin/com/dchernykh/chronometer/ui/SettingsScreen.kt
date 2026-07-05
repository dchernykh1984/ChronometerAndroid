package com.dchernykh.chronometer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.dchernykh.chronometer.data.isUploadConfigured
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChronometerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(viewModel.loadSettings()) }
    var storageGranted by remember { mutableStateOf(StoragePermission.isGranted(context)) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
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

            val sendMisconfigured = settings.sendEnabled && !settings.isUploadConfigured
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

            Button(
                onClick = {
                    viewModel.saveSettings(settings)
                    onBack()
                },
                enabled = !sendMisconfigured,
                modifier = Modifier.fillMaxWidth().testTag("saveButton"),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}
