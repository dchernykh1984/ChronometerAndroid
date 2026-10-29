package com.dchernykh.chronometer.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A raw device file of the data folder ([RawFiles]): a batch's lines appended together, and
 * a warning that stays once the file missed some -- it only grows, so lines it missed stay
 * missing -- until a new competition starts it afresh.
 */
class RawLog(
    private val name: String,
    private val writer: BackupWriter,
) {
    private val failedState = MutableStateFlow(false)

    val failed: StateFlow<Boolean> = failedState.asStateFlow()

    suspend fun append(
        folderPath: String,
        lines: List<String>,
    ) {
        val written = withContext(Dispatchers.IO) { writer.appendRaw(folderPath, name, lines) }
        failedState.value = failedState.value || !written
    }

    fun startOver() {
        failedState.value = false
    }
}
