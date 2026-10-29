# Implementation Plan: Cutoff sources -- by hand, light beam and timing chips

**Branch**: `feat/event-sources` | **Date**: 2026-10-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-event-sources/spec.md`

## Summary

The referee picks a cutoff source in the settings: by hand (default), a light beam or chips, and
a device for it. While timing mode is on, the timing-mode service runs the device's source -- a
coroutine flow of the events the desktop connector protocol defines -- under a supervisor that
restarts it after a growing pause. Beam breaks wait in a queue for the referee's number; chip reads
go through a map built from `start.txt` and the pass rule shared with the desktop, and become
cutoffs on the existing durable path. A settings button downloads `start.txt` from the site. Only
the test beam and test chips work in this round; the real devices are stubs.

## Technical Context

**Language/Version**: Kotlin 2.2 (pinned toolchain: AGP 8.13.2, compileSdk 36, Compose BOM
2025.07.00)

**Primary Dependencies**: Room, WorkManager, Compose Material 3, coroutines -- all already in the
build; `org.json` from the platform. No new dependency.

**Storage**: Room v3 (new tables, `cutoffs` unchanged); files in the data folder

**Testing**: JUnit + Robolectric + MockWebServer + coroutines-test (unit, Kover gate 65%);
instrumented Compose tests on the phone (pixel_6, API 34) and tablet (pixel_c, API 30) profiles;
`MigrationTest` for 2 -> 3

**Target Platform**: Android 7.0 (API 24) and later

**Project Type**: mobile application

**Performance Goals**: a burst of 10,000 reads stored and handled in order; the main thread never
touches Room or files

**Constraints**: constitution II-IV: Room before files, nothing downloaded on its own, nothing stops
hand recording; detekt and ktlint as configured

**Scale/Scope**: up to about 1000 bibs with 3 chips each; races up to 12 hours

## Constitution Check

| Principle | How the plan meets it |
| --- | --- |
| I. Cutoffs only | Sources make `number#time#event#` lines; no laps or protocol. |
| II. Durable first | Reads, passes, pending breaks and cutoffs commit to Room in one transaction before files and upload; deletions keep their backups as today. |
| III. Offline first | The start protocol is downloaded only by its button; uploads unchanged. |
| IV. Nothing stops the referee | Sources run in the service's scope off the main thread; failures restart with backoff and show on screen; Cutoff by hand never waits for a source. |
| V. Formats are a shared contract | `results.txt` unchanged; field 11 read by index; raw files and events as ChronometerPython documents. |
| VI. Three languages | Every new string in `values`, `values-ru`, `values-kk`; device names are strings, tokens are not. |
| VII. Pinned toolchain | No dependency added or bumped. |
| VIII. Tests and plain history | Unit tests for every new class, instrumented tests for the screens, migration test; one commit per task. |

No violation; Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-event-sources/
  spec.md
  plan.md            this file
  research.md        decisions and their reasons
  data-model.md      settings, Room v3, files
  contracts/
    README.md        which shared contracts apply, and where they live
  tasks.md           ordered tasks, one commit each
```

### Source Code

```text
app/src/main/kotlin/com/dchernykh/chronometer/
  data/
    AppDatabase.kt          v3: ChipReadEntity (with its bib), PassEntity, PendingBreakEntity
    Migrations.kt           MIGRATION_2_3
    ChipDao.kt, PassDao.kt, BeamDao.kt   reads and waiting reads, passes, pending breaks
    CutoffRepository.kt     afterChange(): revision, snapshot, upload, shared by every path
    Settings.kt, SettingsStore.kt   cutoff source, devices, dead time
  source/
    SourceEvent.kt          ChipRead, BeamBreak, Status, Error
    Devices.kt              DeviceId: kind, available, test, label
    TestBeamSource.kt, TestChipsSource.kt
    SourceRunner.kt         supervisor: backoff, state
    SourceController.kt     app-wide: which source runs, its state, the event pipeline
  chips/
    ChipMap.kt              start.txt -> chip map, conflicts
    ChipRepository.kt       the pass rule: batch -> transaction -> cutoffs; retro on a new map;
                            unknown chips
  beam/
    BeamRepository.kt       pending queue, assign oldest, discard
  startprotocol/
    StartProtocolFile.kt    read (UTF-8, then cp1251), replace with backup
    StartListClient.kt      GET start-list
  service/RaceService.kt    starts and stops the controller with timing mode
  ui/
    MainScreen.kt           source line, test warning, pending breaks, Cutoff label
    SettingsScreen.kt       source, device, dead time, Download start protocol
    ChipsScreen.kt          conflicts, unknown chips, Reload
app/src/main/res/values{,-ru,-kk}/strings.xml
app/src/test/resources/chip-pass-vectors.json
app/schemas/com.dchernykh.chronometer.data.AppDatabase/3.json
```

**Structure Decision**: new packages `source`, `chips`, `beam` and `startprotocol` beside the
existing ones; the UI stays in `ui`. `ChipsScreenKt`, `PendingBreaksKt`, `SourceLineKt` and
`SourceSettingsKt` join the Kover exclusions with the other screens, which the instrumented
tests cover.

## Design notes

- **Pipeline**: `SourceRunner` sends events into a channel, an event its device did not time
  timed as it arrives; one consumer coroutine drains up to 200 waiting events and hands chip
  reads to `ChipRepository.apply()` and beam breaks to `BeamRepository.add()`, both under
  `CutoffRepository.mutationMutex` so they never interleave with a hand cutoff, an edit or a
  deletion. The consumer runs in the controller's own scope: what a source sent is stored after
  a stop too, and a restart's consumer starts after the one before. A batch that cannot be
  stored is tried again after a pause growing from 1 s to 30 s and shown as the last error.
  Each event carries the competition it arrived in, counted by `CutoffRepository`; one from a
  competition since replaced is dropped under the lock, where a new competition starts.
  Saving the settings restarts a running source only when they choose another device.
- **`CutoffRepository`**: `record()` keeps its behaviour; the part after the Room insert (revision,
  snapshot, upload) becomes `afterChange(stamp)`, used by hand cutoffs, chip cutoffs and assigned
  breaks. A batch of source events calls it once, so a mass finish writes one `results.txt` and one
  snapshot per batch, not one per cutoff: snapshots hold the whole list, and one per chip cutoff
  would grow the backup folder with the square of the field size.
- **Main screen**: pending beam breaks show above the log, oldest first, each with Discard, and
  stay after the source changes; the Cutoff button reads "Cutoff 10:00:00.123" while one is pending. In chips mode a line
  shows source state, bibs and chips loaded, conflicts and unknown chips, and opens the chips
  screen. A test device adds a warning line.
- **Settings**: a "Cutoff source" section with the three choices, the device list (stubs disabled,
  "not available yet"), the dead time for chips, and "Download start protocol" with its result.
  The press saves the form first, so the download uses the site URL, token and folder on screen.

## Complexity Tracking

Empty: no constitution violation.
