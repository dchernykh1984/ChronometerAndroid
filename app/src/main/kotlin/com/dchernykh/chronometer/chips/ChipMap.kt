package com.dchernykh.chronometer.chips

import java.math.BigInteger

private const val STAGES_FIELD = 4
private const val CHIPS_FIELD = 11
private const val NOT_PAID = "NOT_PAID"

/** How every chip id is compared: upper case, ASCII letters and digits only (StartProtocolMaker's rule). */
fun normaliseChip(text: String): String = text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.uppercase()

/**
 * (chip, bib) for every chip a start protocol line binds (spec FR-008), as ChronometerPython's
 * `chip_bindings`: field 11 holds the riders of a team in stage order, split by `;`, and each
 * rider's ids split by `,`. On a line with N > 1 stages and a numeric bib the k-th rider binds
 * to bib + k and riders past the N-th bind nothing; any other line binds every chip to its bib.
 */
internal fun chipBindings(line: String): List<Pair<String, String>> {
    val fields = line.split('#')
    val bib = fields[0].trim()
    if (bib.isEmpty() || bib == NOT_PAID || fields.size <= CHIPS_FIELD + 1) {
        return emptyList()
    }
    val stages =
        fields[STAGES_FIELD]
            .trim()
            .takeIf { decimal(it) && decimal(bib) }
            ?.toBigInteger()
            ?.takeIf { it > BigInteger.ONE }
    val riders = fields[CHIPS_FIELD].split(';')
    val bound = stages?.min(riders.size.toBigInteger())?.toInt() ?: riders.size
    return riders.take(bound).flatMapIndexed { k, rider ->
        val riderBib = if (stages != null && k > 0) (bib.toBigInteger() + k.toBigInteger()).toString() else bib
        rider
            .split(',')
            .map(::normaliseChip)
            .filter(String::isNotEmpty)
            .distinct()
            .map { it to riderBib }
    }
}

// Unicode decimal digits, as Python's str.isdecimal(), so both chronometers bind alike.
private fun decimal(text: String) = text.isNotEmpty() && text.all(Char::isDigit)

/** Chip id to bib from the lines of start.txt; a chip bound to two bibs is a conflict and maps to neither. */
data class ChipMap(
    val bibOf: Map<String, String> = emptyMap(),
    val conflicts: Map<String, List<String>> = emptyMap(),
    /** Lines that bind no chip: no bib, `NOT_PAID`, no chip ids. */
    val unboundLines: Int = 0,
) {
    /** How many bibs have a chip. */
    val bibs: Int get() = bibOf.values.toSet().size

    companion object {
        fun of(lines: List<String>): ChipMap {
            val owners = LinkedHashMap<String, MutableList<String>>()
            var unbound = 0
            for (line in lines) {
                val pairs = chipBindings(line)
                if (pairs.isEmpty()) {
                    unbound++
                }
                for ((chip, bib) in pairs) {
                    val bibs = owners.getOrPut(chip) { mutableListOf() }
                    if (bib !in bibs) {
                        bibs += bib
                    }
                }
            }
            val (single, shared) = owners.entries.partition { it.value.size == 1 }
            return ChipMap(
                bibOf = single.associate { it.key to it.value.single() },
                conflicts = shared.associate { it.key to it.value.toList() },
                unboundLines = unbound,
            )
        }
    }
}
