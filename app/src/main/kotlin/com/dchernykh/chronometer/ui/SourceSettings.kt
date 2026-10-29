package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.source.DeviceId
import com.dchernykh.chronometer.source.SourceKind

private const val DEAD_TIME_DIGITS = 3

/** Where cutoffs come from, the device that makes them and, for chips, the dead time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CutoffSourceSection(
    settings: Settings,
    onChange: (Settings) -> Unit,
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
    val chosen = if (kind == SourceKind.BEAM) settings.beamDevice else settings.chipsDevice
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
            value = settings.deadTimeS.toString(),
            onValueChange = { typed ->
                onChange(
                    settings.copy(deadTimeS = typed.filter(Char::isDigit).take(DEAD_TIME_DIGITS).toIntOrNull() ?: 0),
                )
            },
            label = { Text(stringResource(R.string.dead_time)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("deadTimeField"),
        )
    }
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
