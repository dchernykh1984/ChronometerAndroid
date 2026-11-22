package com.dchernykh.chronometer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.Settings
import com.dchernykh.chronometer.data.chosenDevice
import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.EventProblem
import com.dchernykh.chronometer.source.OpenFailure
import com.dchernykh.chronometer.source.SourceError
import com.dchernykh.chronometer.source.SourceState
import com.dchernykh.chronometer.source.SourceStatus
import com.dchernykh.chronometer.time.ClockNews
import com.dchernykh.chronometer.time.ClockReading
import com.dchernykh.chronometer.time.ClockSource
import com.dchernykh.chronometer.time.GnssVerdict
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** The chosen device and what it is doing, its last error and a test-data warning. */
@Composable
internal fun SourceLine(
    settings: Settings,
    status: SourceStatus,
) {
    val device = settings.chosenDevice ?: return
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

/**
 * Where the time comes from and how far it may be off, read again every second, and what the
 * referee is to be told of the clock (spec 003, FR-009).
 */
@Composable
internal fun ClockLine(
    reading: () -> ClockReading,
    news: ClockNews?,
) {
    var now by remember { mutableStateOf(reading()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_EVERY_MS)
            now = reading()
        }
    }
    val within = now.withinMs?.roundToInt()
    Text(
        text =
            when {
                within == null -> stringResource(R.string.clock_line_system)
                now.source == ClockSource.GNSS -> stringResource(R.string.clock_line_gnss, within)
                else -> stringResource(R.string.clock_line_internet, within)
            },
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.testTag("clockLine"),
    )
    news?.let {
        Text(
            text = clockNewsText(it),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("clockNews"),
        )
    }
}

private const val CLOCK_EVERY_MS = 1_000L

@Composable
private fun clockNewsText(news: ClockNews): String =
    when (news) {
        is ClockNews.Corrected ->
            stringResource(
                R.string.clock_news_corrected,
                String.format(Locale.ROOT, "%+.3f", news.byMs / 1_000.0),
                stringResource(
                    when (news.source) {
                        ClockSource.GNSS -> R.string.clock_source_gnss
                        ClockSource.INTERNET -> R.string.clock_source_internet
                        ClockSource.SYSTEM -> R.string.clock_source_system
                    },
                ),
            )
        is ClockNews.GnssDropped ->
            stringResource(
                when (news.why) {
                    GnssVerdict.FAR_FROM_INTERNET -> R.string.clock_news_gnss_far_internet
                    GnssVerdict.FAR_FROM_SYSTEM -> R.string.clock_news_gnss_far_system
                    else -> R.string.clock_news_gnss_unsteady
                },
            )
        ClockNews.NoLocation -> stringResource(R.string.clock_news_no_location)
    }

@Composable
private fun errorText(error: SourceError): String =
    when (error) {
        is SourceError.Device -> stringResource(R.string.source_error_device, error.message)
        is SourceError.Failed -> stringResource(R.string.source_error_failed, error.message)
        SourceError.Ended -> stringResource(R.string.source_error_ended)
        is SourceError.NotStored -> stringResource(R.string.source_error_not_stored, error.message)
        is SourceError.Dropped -> stringResource(R.string.source_error_dropped, error.events, error.message)
        is SourceError.Board -> boardText(error.trouble)
        is SourceError.Invalid ->
            stringResource(
                when (error.problem) {
                    EventProblem.NO_CHIP -> R.string.source_error_no_chip
                    EventProblem.TIME_OUT_OF_RANGE -> R.string.source_error_time
                },
            )
    }

@Composable
private fun boardText(trouble: BoardTrouble): String =
    when (trouble) {
        BoardTrouble.NotFound -> stringResource(R.string.source_board_not_found)
        BoardTrouble.NoPermission -> stringResource(R.string.source_board_no_permission)
        BoardTrouble.NotNamed,
        is BoardTrouble.NotInRange,
        BoardTrouble.BluetoothOff,
        BoardTrouble.NoBluetoothLeave,
        -> radioText(trouble)
        is BoardTrouble.NotOpened -> notOpenedText(trouble)
        BoardTrouble.Lost -> stringResource(R.string.source_board_lost)
        BoardTrouble.Silent -> stringResource(R.string.source_board_silent)
        BoardTrouble.Blocked -> stringResource(R.string.source_board_blocked)
        BoardTrouble.Unsteady -> stringResource(R.string.source_board_unsteady)
        is BoardTrouble.OtherFirmware ->
            stringResource(R.string.source_board_other_firmware, trouble.runs, trouble.wanted)
        is BoardTrouble.BreaksMissing ->
            if (trouble.first == trouble.last) {
                stringResource(R.string.source_board_break_missing, trouble.first)
            } else {
                stringResource(R.string.source_board_breaks_missing, trouble.first, trouble.last)
            }
        is BoardTrouble.ClockNotRead -> stringResource(R.string.source_board_clock_not_read, trouble.breaks)
    }

/** The Bluetooth beam's own troubles in finding its board. */
@Composable
private fun radioText(trouble: BoardTrouble): String =
    when (trouble) {
        is BoardTrouble.NotInRange -> stringResource(R.string.source_board_not_in_range, trouble.name)
        BoardTrouble.BluetoothOff -> stringResource(R.string.source_board_bluetooth_off)
        BoardTrouble.NoBluetoothLeave -> stringResource(R.string.source_board_no_bluetooth_leave)
        else -> stringResource(R.string.source_board_not_named)
    }

@Composable
private fun notOpenedText(trouble: BoardTrouble.NotOpened): String =
    when (trouble.why) {
        OpenFailure.HELD -> stringResource(R.string.source_board_held)
        OpenFailure.NO_PORT -> stringResource(R.string.source_board_no_port)
        OpenFailure.REFUSED -> stringResource(R.string.source_board_refused)
        OpenFailure.OTHER -> stringResource(R.string.source_board_not_opened, trouble.words)
    }
