package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.chips.ChipMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/** A chip no start protocol holds, so the unknown chips have something to show. */
const val UNKNOWN_TEST_CHIP = "TEST0000"

private const val PERIOD_MS = 3_000L
private const val UNKNOWN_EVERY = 10
private const val FEWEST_READS = 2
private const val MOST_READS = 4
private const val SOONEST_NEXT_MS = 50L
private const val LATEST_NEXT_MS = 300L
private const val ANTENNAS = 4
private const val WEAKEST_DECI_DBM = -750
private const val STRONGEST_DECI_DBM = -450

/**
 * The test chips (FR-004), as ChronometerPython's: every 3 s the next rider of the loaded
 * map passes, read two to four times within a second by their chips; every tenth pass, and
 * every pass without a map, reads [UNKNOWN_TEST_CHIP].
 */
class TestChipsSource(
    private val chipMap: () -> ChipMap,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : CutoffSource {
    override fun events(): Flow<SourceEvent> =
        flow {
            var passes = 0
            while (true) {
                passes++
                val riders =
                    chipMap()
                        .bibOf.entries
                        .groupBy({ it.value }, { it.key })
                        .values
                        .toList()
                val chips =
                    riders.takeIf { it.isNotEmpty() && passes % UNKNOWN_EVERY != 0 }?.let { it[(passes - 1) % it.size] }
                        ?: listOf(UNKNOWN_TEST_CHIP)
                repeat(random.nextInt(FEWEST_READS, MOST_READS + 1)) { i ->
                    delay(if (i == 0) PERIOD_MS else random.nextLong(SOONEST_NEXT_MS, LATEST_NEXT_MS + 1))
                    emit(
                        SourceEvent.ChipRead(
                            chip = chips.random(random),
                            timeMs = clock(),
                            antenna = random.nextInt(1, ANTENNAS + 1).toString(),
                            rssi = random.nextInt(WEAKEST_DECI_DBM, STRONGEST_DECI_DBM + 1) / 10.0,
                            reader = "test",
                        ),
                    )
                }
            }
        }
}
