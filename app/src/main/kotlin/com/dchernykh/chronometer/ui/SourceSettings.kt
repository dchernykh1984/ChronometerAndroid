package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.DEAD_TIME_RANGE_S
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.data.chosenDevice
import com.dchernykh.chronometer.source.DeviceId
import com.dchernykh.chronometer.source.SourceKind
import com.dchernykh.chronometer.startprotocol.Download
import com.dchernykh.chronometer.startprotocol.DownloadProblem

private const val DEAD_TIME_DIGITS = 3

/**
 * Where cutoffs come from, the device that makes them and, for chips, the dead time and the
 * start protocol download.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CutoffSourceSection(
    settings: Settings,
    onChange: (Settings) -> Unit,
    download: Download?,
    canDownload: Boolean,
    onDownload: () -> Unit,
) {
    Text(stringResource(R.string.cutoff_source))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((kind, label, tag) in KINDS) {
            ChoiceChip(label, tag, settings.cutoffSource == kind) {
                onChange(settings.copy(cutoffSource = kind))
            }
        }
    }
    val kind = settings.cutoffSource
    if (kind == SourceKind.HAND) {
        return
    }
    Text(stringResource(R.string.source_device))
    val chosen = settings.chosenDevice
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (device in DeviceId.of(kind)) {
            FilterChip(
                selected = device == chosen,
                enabled = device.available,
                onClick = {
                    onChange(
                        if (kind == SourceKind.BEAM) {
                            settings.copy(beamDevice = device)
                        } else {
                            settings.copy(chipsDevice = device)
                        },
                    )
                },
                label = { Text(deviceLabel(device), maxLines = 1) },
                modifier = Modifier.testTag("device${device.name}"),
            )
        }
    }
    if (kind == SourceKind.CHIPS) {
        OutlinedTextField(
            // Cleared, the field stays empty: a "0" before the cursor would make 3 typed read 30.
            value = if (settings.deadTimeS == 0) "" else settings.deadTimeS.toString(),
            onValueChange = { typed ->
                onChange(
                    settings.copy(deadTimeS = typed.filter(Char::isDigit).take(DEAD_TIME_DIGITS).toIntOrNull() ?: 0),
                )
            },
            label = { Text(stringResource(R.string.dead_time, DEAD_TIME_RANGE_S.first, DEAD_TIME_RANGE_S.last)) },
            isError = settings.deadTimeS !in DEAD_TIME_RANGE_S,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("deadTimeField"),
        )
        StartListDownload(download, canDownload, onDownload)
    }
}

/** "Download start protocol" and what the last download did (FR-009). */
@Composable
private fun StartListDownload(
    download: Download?,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    OutlinedButton(
        onClick = onDownload,
        enabled = enabled && download != Download.Running,
        modifier = Modifier.fillMaxWidth().testTag("downloadStartProtocol"),
    ) { Text(stringResource(R.string.download_start_protocol)) }
    Text(stringResource(R.string.download_saves_first), style = MaterialTheme.typography.bodySmall)
    download?.let {
        Text(
            text = downloadText(it),
            color = if (it is Download.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            modifier = Modifier.testTag("downloadState"),
        )
    }
}

@Composable
private fun downloadText(download: Download): String =
    when (download) {
        Download.Running -> stringResource(R.string.download_running)
        is Download.Done -> {
            val map = download.map
            val done =
                stringResource(R.string.download_done, download.lines, map.bibs, map.bibOf.size, map.conflicts.size)
            val conflicts =
                map.conflicts.entries
                    .sortedBy {
                        it.key
                    }.joinToString("; ") { (chip, bibs) -> "$chip (${bibs.joinToString()})" }
            if (conflicts.isEmpty()) done else done + "\n" + stringResource(R.string.download_conflicts, conflicts)
        }
        is Download.Failed ->
            if (download.problem == DownloadProblem.UNUSABLE) {
                stringResource(R.string.download_unusable, download.detail)
            } else {
                stringResource(R.string.download_failed, problemText(download))
            }
    }

@Composable
private fun problemText(failed: Download.Failed): String =
    when (failed.problem) {
        DownloadProblem.NO_SITE -> stringResource(R.string.download_no_site)
        DownloadProblem.NETWORK -> stringResource(R.string.download_network, failed.detail)
        DownloadProblem.HTTP -> stringResource(R.string.download_http, failed.detail)
        DownloadProblem.INVALID -> stringResource(R.string.download_invalid, failed.detail)
        DownloadProblem.NOT_A_START_LIST -> stringResource(R.string.download_not_a_start_list)
        DownloadProblem.BAD_LINE -> stringResource(R.string.download_bad_line)
        DownloadProblem.UNUSABLE -> stringResource(R.string.download_unusable, failed.detail)
        DownloadProblem.STALE -> stringResource(R.string.download_stale)
        DownloadProblem.EMPTY -> stringResource(R.string.download_empty)
        DownloadProblem.FILE -> stringResource(R.string.download_file)
    }

private val KINDS =
    listOf(
        Triple(SourceKind.HAND, R.string.source_hand, "sourceHand"),
        Triple(SourceKind.BEAM, R.string.source_beam, "sourceBeam"),
        Triple(SourceKind.CHIPS, R.string.source_chips, "sourceChips"),
    )

/** A device's name in the referee's language, marked when it is not available yet. */
@Composable
internal fun deviceLabel(device: DeviceId): String {
    val name =
        stringResource(
            when (device) {
                DeviceId.TEST_BEAM -> R.string.device_test_beam
                DeviceId.USB_BEAM -> R.string.device_usb_beam
                DeviceId.BLE_BEAM -> R.string.device_ble_beam
                DeviceId.TEST_CHIPS -> R.string.device_test_chips
                DeviceId.LLRP -> R.string.device_llrp
                DeviceId.RACERESULT -> R.string.device_raceresult
                DeviceId.R2000 -> R.string.device_r2000
            },
        )
    return if (device.available) name else stringResource(R.string.device_not_available, name)
}
