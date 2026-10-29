package com.dchernykh.chronometer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.chips.StartProtocol
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.source.SourceKind

/** In chips mode: what the chip map holds, or why there is none; opens the chips screen. */
@Composable
internal fun ChipsLine(
    settings: Settings,
    protocol: StartProtocol?,
    onOpen: () -> Unit,
) {
    if (settings.cutoffSource != SourceKind.CHIPS || protocol == null) {
        return
    }
    Text(
        text = protocolSummary(protocol),
        style = MaterialTheme.typography.labelMedium,
        color = if (protocol is StartProtocol.Loaded) Color.Unspecified else MaterialTheme.colorScheme.error,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag("chipsLine"),
    )
}

@Composable
private fun protocolSummary(protocol: StartProtocol): String =
    when (protocol) {
        StartProtocol.Missing -> stringResource(R.string.chips_no_start_protocol)
        is StartProtocol.Unreadable -> stringResource(R.string.chips_start_protocol_unreadable, protocol.reason)
        is StartProtocol.Loaded ->
            stringResource(
                R.string.chips_map_summary,
                protocol.map.bibs,
                protocol.map.bibOf.size,
                protocol.map.conflicts.size,
            )
    }

/** The chip map: its counts, the chips on two bibs, and Reload for a start.txt put there by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipsScreen(
    viewModel: ChronometerViewModel,
    onBack: () -> Unit,
) {
    val protocol by viewModel.startProtocol.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chips_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("chipsBackButton")) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                protocol?.let { Text(protocolSummary(it), modifier = Modifier.testTag("chipsSummary")) }
            }
            item {
                Button(
                    onClick = viewModel::reloadStartProtocol,
                    modifier = Modifier.fillMaxWidth().testTag("reloadStartProtocol"),
                ) { Text(stringResource(R.string.reload_start_protocol)) }
            }
            (protocol as? StartProtocol.Loaded)?.map?.let { map ->
                item { Text(stringResource(R.string.chips_unbound_lines, map.unboundLines)) }
                val heading =
                    if (map.conflicts.isEmpty()) R.string.chips_no_conflicts else R.string.chips_conflicts_title
                item { Text(stringResource(heading), style = MaterialTheme.typography.titleSmall) }
                items(map.conflicts.entries.sortedBy { it.key }, key = { it.key }) { (chip, bibs) ->
                    Text("$chip: ${bibs.joinToString()}", modifier = Modifier.testTag("chipConflict"))
                }
            }
        }
    }
}
