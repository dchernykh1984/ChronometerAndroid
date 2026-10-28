# Contracts

This feature implements contracts that ChronometerPython documents for both chronometers, in its
`specs/001-event-sources/contracts/`:

- `connector-protocol.md`: the events a source reports. On the phone a source is a Kotlin flow of
  the same events (`read`, `beam`, `status`, `error`); `hello`, `ping` and the process lifecycle
  have no counterpart, because a source runs inside the app.
- `raw-files.md`: `chip_reads.txt` and `beam_breaks.txt`.
- `chip-pass-vectors.json`: the cases the pass rule must satisfy; copied into
  `app/src/test/resources/` and run by the unit tests.

And the start protocol line with field 11, documented by StartProtocolMakerPython in
`specs/002-chip-ids/contracts/start-protocol-line.md`.
