package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.PendingBreakEntity

/** The time of day of a `D H:M:S.mmm` time, as the referee reads it. */
internal fun timeOfDay(timeStr: String): String = timeStr.substringAfter(' ')

/** "Cutoff", or "Cutoff 10:00:00.123" while a beam break waits: the time Cutoff will use. */
@Composable
internal fun cutoffLabel(next: PendingBreakEntity?): String =
    if (next == null) {
        stringResource(R.string.cutoff)
    } else {
        stringResource(R.string.cutoff_at, timeOfDay(next.timeStr))
    }

/** Beam breaks waiting for a number, oldest first, each discardable after a confirmation. */
@Composable
internal fun PendingBreaks(
    pending: List<PendingBreakEntity>,
    onDiscard: (Long) -> Unit,
) {
    if (pending.isEmpty()) {
        return
    }
    var asking by remember { mutableStateOf<PendingBreakEntity?>(null) }
    Text(stringResource(R.string.pending_breaks_title), style = MaterialTheme.typography.labelMedium)
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("pendingBreaks")) {
        items(pending, key = { it.id }) { item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(timeOfDay(item.timeStr), modifier = Modifier.weight(1f).testTag("pendingBreak"))
                TextButton(onClick = { asking = item }, modifier = Modifier.testTag("discardBreak")) {
                    Text(stringResource(R.string.discard))
                }
            }
        }
    }
    asking?.let { item ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.discard_break_title)) },
            text = { Text(stringResource(R.string.discard_break_message, timeOfDay(item.timeStr))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscard(item.id)
                        asking = null
                    },
                    modifier = Modifier.testTag("confirmDiscardBreak"),
                ) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
