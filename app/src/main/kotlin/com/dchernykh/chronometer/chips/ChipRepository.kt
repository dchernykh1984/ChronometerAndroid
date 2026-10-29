package com.dchernykh.chronometer.chips

import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.startprotocol.StartProtocolFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/** What `start.txt` gave the chip map; [map] is empty without a usable file. */
sealed interface StartProtocol {
    val map: ChipMap get() = ChipMap()

    data object Missing : StartProtocol

    data class Unreadable(
        val reason: String,
    ) : StartProtocol

    data class Loaded(
        override val map: ChipMap,
    ) : StartProtocol
}

/** The chips side of timing (spec US2, US3): the map from `start.txt`. */
class ChipRepository(
    private val settingsStore: SettingsStore,
) {
    private val protocolState = MutableStateFlow<StartProtocol?>(null)

    /** `start.txt` as last read; null before the first read. */
    val protocol: StateFlow<StartProtocol?> = protocolState.asStateFlow()

    /** Read `start.txt` if it has not been read yet: at start-up, never from the site. */
    suspend fun ensureMap() {
        if (protocolState.value == null) {
            reloadMap()
        }
    }

    /** Read `start.txt` from the data folder again and rebuild the map. */
    suspend fun reloadMap() {
        val folder = settingsStore.load().folderPath
        protocolState.value =
            withContext(Dispatchers.IO) {
                try {
                    StartProtocolFile.read(folder)?.let { StartProtocol.Loaded(ChipMap.of(it)) }
                        ?: StartProtocol.Missing
                } catch (e: IOException) {
                    StartProtocol.Unreadable(e.message ?: e.javaClass.simpleName)
                }
            }
    }
}
