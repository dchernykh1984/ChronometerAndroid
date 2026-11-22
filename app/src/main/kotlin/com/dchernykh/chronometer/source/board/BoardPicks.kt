package com.dchernykh.chronometer.source.board

/** How a beam board is reached. */
enum class BoardLink {
    USB,
    BLE,
}

/**
 * A board the referee picked in the list of the boards around (ChronometerPython's spec 003,
 * FR-002): on Bluetooth by its address, on USB the board on the phone's USB, whichever it is
 * (one at a time on a phone's port); [name] as the list showed it.
 */
data class BoardPick(
    val link: BoardLink,
    val key: String,
    val name: String,
)

/** The key of the board on the phone's USB. */
const val USB_KEY = "usb"

/** A board found around: its pick, and its signal on Bluetooth, in dBm. */
data class BoardFound(
    val pick: BoardPick,
    val rssi: Int? = null,
)

/** The picks as one setting, a line each: link, key and name, tab apart. */
fun encodePicks(picks: List<BoardPick>): String = picks.joinToString("\n") { "${it.link.name}\t${it.key}\t${it.name}" }

/** The picks back from [encodePicks]; a line that is not one is left out. */
fun decodePicks(text: String): List<BoardPick> =
    text.lines().mapNotNull { line ->
        val fields = line.split("\t")
        val link = BoardLink.entries.firstOrNull { it.name == fields.firstOrNull() }
        if (link == null || fields.size != 3 || fields[1].isBlank()) null else BoardPick(link, fields[1], fields[2])
    }

/** A row of the list: a board, whether it is picked, and whether the last search found it. */
data class BoardRow(
    val board: BoardFound,
    val picked: Boolean,
    val here: Boolean,
)

/**
 * Whether this pick is the board [found]: by its key, or, picked by its name in an older
 * version (the Bluetooth beam's typed name), by the name the board advertises.
 */
fun BoardPick.isBoard(found: BoardPick): Boolean {
    if (key == found.key) return true
    val radio = link == BoardLink.BLE && found.link == BoardLink.BLE
    return radio && found.name.isNotEmpty() && key.equals(found.name, ignoreCase = true)
}

/** The list: the boards found, then those picked that the last search did not find; each board once. */
fun boardRows(
    found: List<BoardFound>,
    picks: List<BoardPick>,
): List<BoardRow> =
    found.map { board -> BoardRow(board, picks.any { it.isBoard(board.pick) }, here = true) } +
        picks
            .filter { pick -> found.none { pick.isBoard(it.pick) } }
            .map { BoardRow(BoardFound(it), picked = true, here = false) }
