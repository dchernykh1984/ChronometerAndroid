package com.dchernykh.chronometer.io

import java.math.BigDecimal
import java.math.MathContext

/** The raw device files in the data folder, in ChronometerPython's formats. */
object RawFiles {
    const val BEAM_BREAKS = "beam_breaks.txt"
    const val CHIP_READS = "chip_reads.txt"

    // Every line break a reader may split at (Python's str.splitlines() among them).
    internal val LINE_BREAKS = Regex("[\\r\\n\\u000B\\u000C\\u001C\\u001D\\u001E\\u0085\\u2028\\u2029]")

    /** Device text made safe for one field of a `#`-separated line. */
    fun field(text: String): String = LINE_BREAKS.replace(text.replace('#', ' '), " ").trim()

    /** A signal strength as ChronometerPython writes it (`%g`): -63.0 is "-63", -58.5 "-58.5". */
    fun signal(rssi: Double?): String =
        rssi
            ?.takeIf {
                it.isFinite()
            }?.let { BigDecimal(it).round(MathContext(SIGNAL_DIGITS)).stripTrailingZeros().toPlainString() }
            .orEmpty()

    private const val SIGNAL_DIGITS = 6
}
