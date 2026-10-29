package com.dchernykh.chronometer.io

/** The raw device files in the data folder, in ChronometerPython's formats. */
object RawFiles {
    const val BEAM_BREAKS = "beam_breaks.txt"

    // Every line break a reader may split at (Python's str.splitlines() among them).
    private val LINE_BREAKS = Regex("[\\r\\n\\u000B\\u000C\\u001C\\u001D\\u001E\\u0085\\u2028\\u2029]")

    /** Device text made safe for one field of a `#`-separated line. */
    fun field(text: String): String = LINE_BREAKS.replace(text.replace('#', ' '), " ").trim()
}
