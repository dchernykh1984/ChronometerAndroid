package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.dchernykh.chronometer.source.board.BlinkNote
import com.dchernykh.chronometer.source.board.Blinked
import com.dchernykh.chronometer.source.board.BoardLink
import com.dchernykh.chronometer.source.board.BoardPick
import com.dchernykh.chronometer.source.board.BoardRow
import com.dchernykh.chronometer.source.board.BoardSearchState
import com.dchernykh.chronometer.source.board.boardRows
import com.dchernykh.chronometer.source.board.isBoard
import com.dchernykh.chronometer.startprotocol.Download
import com.dchernykh.chronometer.startprotocol.DownloadProblem

private const val DEAD_TIME_DIGITS = 3

/** The list of the boards around as the settings show it (spec 003), and what it can ask. */
internal class BoardsUi(
    val search: BoardSearchState,
    /** Timing mode on: the pick waits for it to stop, as the next start reads it. */
    val locked: Boolean,
    val note: BlinkNote?,
    val onFind: () -> Unit,
    val onBlink: (BoardPick) -> Unit,
)

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
    boards: BoardsUi,
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
    if (kind == SourceKind.BEAM && chosen == DeviceId.BOARDS) {
        BoardsList(settings, onChange, boards)
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

/**
 * The boards around, found on asking, each with a check that picks it and Blink (spec 003,
 * FR-001 to FR-003); those picked and not found by the last search kept, greyed.
 */
@Composable
private fun BoardsList(
    settings: Settings,
    onChange: (Settings) -> Unit,
    ui: BoardsUi,
) {
    OutlinedButton(
        onClick = ui.onFind,
        enabled = !ui.search.running,
        modifier = Modifier.fillMaxWidth().testTag("findBoards"),
    ) { Text(stringResource(if (ui.search.running) R.string.boards_searching else R.string.boards_find)) }
    ui.search.search
        ?.trouble
        ?.let { Text(boardText(it), color = MaterialTheme.colorScheme.error) }
    val rows =
        boardRows(
            ui.search.search
                ?.found
                .orEmpty(),
            settings.beamBoards,
        )
    if (ui.search.search != null && rows.isEmpty()) Text(stringResource(R.string.boards_none_found))
    for (row in rows) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val pick = row.board.pick
            Checkbox(
                checked = row.picked,
                enabled = !ui.locked,
                onCheckedChange = { checked ->
                    val others = settings.beamBoards.filter { !it.isBoard(pick) } // by its name before too
                    onChange(settings.copy(beamBoards = if (checked) others + pick else others))
                },
                modifier = Modifier.testTag("pick ${pick.key}"),
            )
            Text(
                text = boardLabel(row),
                color = if (row.here) Color.Unspecified else MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.onBlink(pick) }, modifier = Modifier.testTag("blink ${pick.key}")) {
                Text(stringResource(R.string.board_blink))
            }
        }
    }
    if (ui.locked) Text(stringResource(R.string.boards_locked), style = MaterialTheme.typography.bodySmall)
    ui.note?.let { Text(blinkText(it), style = MaterialTheme.typography.bodySmall) }
    Text(stringResource(R.string.board_blink_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun boardLabel(row: BoardRow): String {
    val pick = row.board.pick
    val rssi = row.board.rssi
    return when {
        !row.here -> stringResource(R.string.board_gone, pick.name)
        pick.link == BoardLink.USB -> stringResource(R.string.board_usb, pick.name)
        rssi != null -> stringResource(R.string.board_ble, pick.name, rssi)
        else -> stringResource(R.string.board_ble_no_signal, pick.name)
    }
}

@Composable
private fun blinkText(note: BlinkNote): String =
    stringResource(
        when (note.blinked) {
            Blinked.REACHING -> R.string.blink_reaching
            Blinked.DONE -> R.string.blink_flashing
            Blinked.NOT_CONNECTED -> R.string.blink_not_connected
            Blinked.NOT_FOUND -> R.string.blink_not_found
            Blinked.NOT_PERMITTED -> R.string.blink_not_permitted
            Blinked.NOT_OPENED -> R.string.blink_not_opened
        },
        note.name,
    )

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
                DeviceId.BOARDS -> R.string.device_boards
                DeviceId.TEST_BEAM -> R.string.device_test_beam
                DeviceId.TEST_CHIPS -> R.string.device_test_chips
                DeviceId.LLRP -> R.string.device_llrp
                DeviceId.RACERESULT -> R.string.device_raceresult
                DeviceId.R2000 -> R.string.device_r2000
            },
        )
    return if (device.available) name else stringResource(R.string.device_not_available, name)
}
