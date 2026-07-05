package com.dchernykh.chronometer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.CutoffEntity
import com.dchernykh.chronometer.data.CutoffEvent
import com.dchernykh.chronometer.service.RaceService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChronometerViewModel,
    onOpenSettings: () -> Unit,
) {
    val cutoffs by viewModel.cutoffs.collectAsStateWithLifecycle()
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val backupFailed by viewModel.backupFailed.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var number by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val logState = rememberLazyListState()

    // Keep the newest cutoff in view: jump the log back to the top on each new one.
    LaunchedEffect(cutoffs.firstOrNull()?.id) {
        if (cutoffs.isNotEmpty()) {
            logState.animateScrollToItem(0)
        }
    }

    val context = LocalContext.current
    var eventActive by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start regardless: without the permission the notification is just hidden.
            RaceService.start(context)
            eventActive = true
        }

    fun toggleEvent() {
        if (eventActive) {
            RaceService.stop(context)
            eventActive = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RaceService.start(context)
            eventActive = true
        }
    }

    fun clearAndRefocus() {
        number = ""
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(
                        onClick = ::toggleEvent,
                        modifier = Modifier.testTag("eventToggle"),
                    ) { Text(stringResource(if (eventActive) R.string.event_stop else R.string.event_start)) }
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settingsButton"),
                    ) { Text(stringResource(R.string.settings)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            OutlinedTextField(
                value = number,
                onValueChange = { input ->
                    number = if (settings.numericInput) input.filter(Char::isDigit) else input
                },
                label = { Text(stringResource(R.string.number)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (settings.numericInput) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            viewModel.recordCutoff(number)
                            clearAndRefocus()
                        },
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("numberField"),
            )

            // The cutoff is the primary, high-frequency action: make it big.
            Button(
                onClick = {
                    viewModel.recordCutoff(number)
                    clearAndRefocus()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(top = 8.dp)
                        .testTag("cutoffButton"),
            ) { Text(stringResource(R.string.cutoff), style = MaterialTheme.typography.titleLarge) }

            // Disqualification is rare: keep it small and secondary to avoid mis-taps.
            OutlinedButton(
                onClick = {
                    viewModel.recordDisqualification(number)
                    clearAndRefocus()
                },
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                        .testTag("dsqButton"),
            ) { Text(stringResource(R.string.disqualify), style = MaterialTheme.typography.labelLarge) }

            Diagnostics(
                uploadStatus = uploadStatus,
                backupFailed = backupFailed,
                modifier = Modifier.padding(top = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (cutoffs.isEmpty()) {
                Text(stringResource(R.string.log_empty))
            } else {
                LazyColumn(state = logState, modifier = Modifier.testTag("cutoffLog")) {
                    items(cutoffs, key = { it.id }) { cutoff -> CutoffRow(cutoff) }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Wait for the number field to attach before focusing it, otherwise
        // FocusRequester.requestFocus() throws "not initialized"; never let an
        // auto-focus race crash the screen.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun CutoffRow(cutoff: CutoffEntity) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = cutoff.number,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(72.dp),
        )
        Text(text = cutoff.timeStr, modifier = Modifier.weight(1f))
        Text(
            text = cutoff.event,
            color =
                if (cutoff.event == CutoffEvent.DSQ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun Diagnostics(
    uploadStatus: UploadStatus,
    backupFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = uploadStatusLabel(uploadStatus),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("uploadStatus"),
        )
        if (backupFailed) {
            Text(
                text = stringResource(R.string.diag_backup_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("backupWarning"),
            )
        }
    }
}

@Composable
private fun uploadStatusLabel(status: UploadStatus): String =
    stringResource(
        when (status) {
            UploadStatus.IDLE -> R.string.diag_upload_idle
            UploadStatus.PENDING -> R.string.diag_upload_pending
            UploadStatus.RUNNING -> R.string.diag_upload_running
            UploadStatus.SENT -> R.string.diag_upload_sent
            UploadStatus.FAILED -> R.string.diag_upload_failed
        },
    )
