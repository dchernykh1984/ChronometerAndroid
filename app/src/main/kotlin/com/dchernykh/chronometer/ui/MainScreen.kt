package com.dchernykh.chronometer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
    // Landscape has little vertical room, so shrink the input controls to keep the
    // cutoff log on screen.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Keep the newest cutoff in view: jump the log back to the top on each new one.
    LaunchedEffect(cutoffs.firstOrNull()?.id) {
        if (cutoffs.isNotEmpty()) {
            logState.animateScrollToItem(0)
        }
    }

    val context = LocalContext.current
    // Source of truth is the real service, so the button is correct across
    // recompositions, navigation, recreate and START_STICKY restarts.
    val timingModeOn by RaceService.runningState.collectAsStateWithLifecycle()
    var showTimingOnDialog by remember { mutableStateOf(false) }
    var showTimingDoneDialog by remember { mutableStateOf(false) }

    // While timing mode is on and the app is on screen, keep the screen awake so
    // the phone does not dim or auto-lock during the race.
    val view = LocalView.current
    DisposableEffect(timingModeOn) {
        view.keepScreenOn = timingModeOn
        onDispose { view.keepScreenOn = false }
    }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start regardless: without the permission the notification is just hidden.
            RaceService.start(context)
            showTimingOnDialog = true
        }

    fun toggleTimingMode() {
        if (timingModeOn) {
            RaceService.stop(context)
            showTimingDoneDialog = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RaceService.start(context)
            showTimingOnDialog = true
        }
    }

    fun clearAndRefocus() {
        number = ""
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
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
                    .padding(horizontal = 16.dp, vertical = if (landscape) 8.dp else 16.dp),
        ) {
            // Session-wide control; full width in the content so its long label is
            // never squeezed by the app-bar title.
            OutlinedButton(
                onClick = ::toggleTimingMode,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("timingModeToggle"),
            ) {
                Text(
                    stringResource(
                        if (timingModeOn) R.string.timing_mode_stop else R.string.timing_mode_start,
                    ),
                )
            }

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
                        .height(if (landscape) 52.dp else 72.dp)
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

            HorizontalDivider(modifier = Modifier.padding(vertical = if (landscape) 6.dp else 12.dp))

            if (cutoffs.isEmpty()) {
                Text(stringResource(R.string.log_empty), modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = logState,
                    modifier = Modifier.weight(1f).testTag("cutoffLog"),
                ) {
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

    TimingDialogs(
        showTimingOn = showTimingOnDialog,
        showTimingDone = showTimingDoneDialog,
        onDismissOn = { showTimingOnDialog = false },
        onDismissDone = { showTimingDoneDialog = false },
    )
}

@Composable
private fun TimingDialogs(
    showTimingOn: Boolean,
    showTimingDone: Boolean,
    onDismissOn: () -> Unit,
    onDismissDone: () -> Unit,
) {
    if (showTimingOn) {
        InfoDialog(
            titleRes = R.string.timing_dnd_title,
            messageRes = R.string.timing_dnd_message,
            tag = "timingOnDialog",
            onDismiss = onDismissOn,
        )
    }
    if (showTimingDone) {
        InfoDialog(
            titleRes = R.string.timing_done_title,
            messageRes = R.string.timing_done_message,
            tag = "timingDoneDialog",
            onDismiss = onDismissDone,
        )
    }
}

@Composable
private fun InfoDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    tag: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}Ok")) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        modifier = Modifier.testTag(tag),
    )
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
