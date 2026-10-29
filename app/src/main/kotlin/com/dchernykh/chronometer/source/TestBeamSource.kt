package com.dchernykh.chronometer.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

private const val FASTEST_MS = 5_000L
private const val SLOWEST_MS = 20_000L

/** The test beam: a break every 5 to 20 s at random, at the phone's time (FR-004). */
class TestBeamSource(
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : CutoffSource {
    // Counts on when the runner starts the source again, as a device's own counter would.
    private var seq = 0L

    override fun events(): Flow<SourceEvent> =
        flow {
            while (true) {
                delay(random.nextLong(FASTEST_MS, SLOWEST_MS + 1))
                emit(SourceEvent.BeamBreak(++seq, clock()))
            }
        }
}
