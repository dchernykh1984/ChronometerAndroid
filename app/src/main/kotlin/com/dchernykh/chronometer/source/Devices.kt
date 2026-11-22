package com.dchernykh.chronometer.source

/** Where cutoffs come from: the referee's hand alone, or a device of one kind. */
enum class SourceKind {
    HAND,
    BEAM,
    CHIPS,
    ;

    companion object {
        /** A stored name back to its kind, by hand when it is none. */
        fun fromName(name: String?): SourceKind = entries.firstOrNull { it.name == name } ?: HAND
    }
}

/**
 * The devices a source can run (spec FR-004). The beam boards are those the referee picked in
 * the list of the boards around, on USB and Bluetooth (ChronometerPython's spec 003), listed
 * first; the test ones make events up; the others are listed, "not available yet", and never
 * started.
 */
enum class DeviceId(
    val kind: SourceKind,
    val available: Boolean,
    val test: Boolean,
) {
    BOARDS(SourceKind.BEAM, available = true, test = false),
    TEST_BEAM(SourceKind.BEAM, available = true, test = true),
    TEST_CHIPS(SourceKind.CHIPS, available = true, test = true),
    LLRP(SourceKind.CHIPS, available = false, test = false),
    RACERESULT(SourceKind.CHIPS, available = false, test = false),
    R2000(SourceKind.CHIPS, available = false, test = false),
    ;

    companion object {
        /** A kind's devices, in the order the settings list them. */
        fun of(kind: SourceKind): List<DeviceId> = entries.filter { it.kind == kind }

        /**
         * A stored name back to a device of [kind], or that kind's first: the beam boards, which
         * took the place of the USB and Bluetooth beams, or the test chips.
         */
        fun fromName(
            name: String?,
            kind: SourceKind,
        ): DeviceId = entries.firstOrNull { it.name == name && it.kind == kind } ?: of(kind).first()
    }
}
