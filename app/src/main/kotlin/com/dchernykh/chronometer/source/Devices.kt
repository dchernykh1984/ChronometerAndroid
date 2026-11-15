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
 * The devices a source can run (spec FR-004). The test ones make events up; the USB beam
 * is a beam board on the phone's USB, the Bluetooth beam one over Bluetooth; the others
 * are listed, "not available yet", and never started.
 */
enum class DeviceId(
    val kind: SourceKind,
    val available: Boolean,
    val test: Boolean,
) {
    TEST_BEAM(SourceKind.BEAM, available = true, test = true),
    USB_BEAM(SourceKind.BEAM, available = true, test = false),
    BLE_BEAM(SourceKind.BEAM, available = true, test = false),
    TEST_CHIPS(SourceKind.CHIPS, available = true, test = true),
    LLRP(SourceKind.CHIPS, available = false, test = false),
    RACERESULT(SourceKind.CHIPS, available = false, test = false),
    R2000(SourceKind.CHIPS, available = false, test = false),
    ;

    companion object {
        /** A kind's devices, in the order the settings list them. */
        fun of(kind: SourceKind): List<DeviceId> = entries.filter { it.kind == kind }

        /** A stored name back to a device of [kind], or that kind's test device. */
        fun fromName(
            name: String?,
            kind: SourceKind,
        ): DeviceId = entries.firstOrNull { it.name == name && it.kind == kind } ?: of(kind).first { it.test }
    }
}
