package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.CutoffSource
import com.dchernykh.chronometer.source.SourceEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The beam boards picked (ChronometerPython's spec 003), read at once: each over its own link,
 * through a [BoardBeamSource] of its own, so its own clock and counts. Every break of every board
 * is a cutoff of its own, nothing merged (FR-004); each board's word on itself is named, for the
 * screen to say each one's state (FR-005). [sourceOf] makes a board's source, given what it is
 * to call to claim the board its HELLO names.
 */
class BoardsSource(
    private val picks: List<BoardPick>,
    sourceOf: (BoardPick, (String) -> String?) -> BoardBeamSource,
) : CutoffSource {
    private val readers = mutableMapOf<String, BoardPick>() // by board id, the pick reading it
    private val sources = picks.distinctBy(BoardPick::key).associate { it.key to sourceOf(it) { id -> claim(it, id) } }

    override fun events(): Flow<SourceEvent> {
        if (sources.isEmpty()) {
            return flow {
                emit(SourceEvent.Board(BoardTrouble.NoBoardPicked))
                awaitCancellation()
            }
        }
        return picks
            .distinctBy(BoardPick::key)
            .map { pick -> sources.getValue(pick.key).events().map { it.named(pick.name) } }
            .merge()
    }

    /** The board of [key] blinked; null when it is not one of these, false when not connected. */
    fun blink(key: String): Boolean? = sources[key]?.blink()

    /**
     * The board of [boardId] taken by [pick], unless another pick took it first: then that
     * one's name. One board picked on its USB and its Bluetooth is read once.
     */
    @Synchronized
    private fun claim(
        pick: BoardPick,
        boardId: String,
    ): String? {
        val reader = readers.getOrPut(boardId) { pick }
        return if (reader.key == pick.key) null else reader.name
    }
}

private fun SourceEvent.named(name: String): SourceEvent = if (this is SourceEvent.Board) copy(board = name) else this
