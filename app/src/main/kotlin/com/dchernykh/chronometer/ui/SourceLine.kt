package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.source.EventProblem
import com.dchernykh.chronometer.source.SourceError
import com.dchernykh.chronometer.source.SourceKind
import com.dchernykh.chronometer.source.SourceState
import com.dchernykh.chronometer.source.SourceStatus

/** The chosen device and what it is doing, its last error and a test-data warning. */
@Composable
internal fun SourceLine(
    settings: Settings,
    status: SourceStatus,
) {
    val device =
        when (settings.cutoffSource) {
            SourceKind.HAND -> return
            SourceKind.BEAM -> settings.beamDevice
            SourceKind.CHIPS -> settings.chipsDevice
        }
    val name = deviceLabel(device)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                when (val state = status.state) {
                    SourceState.Stopped -> stringResource(R.string.source_line_stopped, name)
                    SourceState.Running -> stringResource(R.string.source_line_running, name)
                    is SourceState.Restarting -> stringResource(R.string.source_line_restarting, name, state.inSeconds)
                    SourceState.NotAvailable -> stringResource(R.string.source_line_not_available, name)
                },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("sourceLine"),
        )
        status.lastError?.let { error ->
            Text(
                text = errorText(error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                modifier = Modifier.testTag("sourceError"),
            )
        }
        if (status.invalidEvents > 0) {
            Text(
                text = stringResource(R.string.source_invalid_events, status.invalidEvents),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (device.test) {
            Text(
                text = stringResource(R.string.source_test_warning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("sourceTestWarning"),
            )
        }
    }
}

@Composable
private fun errorText(error: SourceError): String =
    when (error) {
        is SourceError.Device -> stringResource(R.string.source_error_device, error.message)
        is SourceError.Failed -> stringResource(R.string.source_error_failed, error.message)
        is SourceError.Invalid ->
            stringResource(
                when (error.problem) {
                    EventProblem.NO_CHIP -> R.string.source_error_no_chip
                    EventProblem.TIME_OUT_OF_RANGE -> R.string.source_error_time
                },
            )
    }
