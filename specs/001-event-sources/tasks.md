# Tasks: Cutoff sources -- by hand, light beam and timing chips

Each task is one commit with the message shown; the tests that prove a task are part of it. Before
each commit: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest koverVerify` with JDK 17, and
`pre-commit run --all-files`. Instrumented tests run in CI on both profiles.

## Phase 1: foundations

- [ ] T001 `CutoffRepository.recordAt(number, timeStr, event)` and `afterChange(stamp)` shared with
  `record()`; unit tests that a cutoff at a given time is stored, snapshotted and queued.
  -- `refactor(cutoffs): record a cutoff at a given time through the same durable path`
- [ ] T002 Room v3: `chip_reads`, `passes`, `attributed_chips`, `pending_breaks`, `ChipDao`, `PassDao`, `BeamDao`,
  `MIGRATION_2_3`, exported schema 3; `MigrationTest` 2 -> 3 keeps every cutoff.
  -- `feat(db): store chip reads, passes and pending beam breaks`

## Phase 2: sources (US1, US5)

- [ ] T003 [US1] `SourceEvent`, `CutoffSource`, `DeviceId` with kinds, stubs and test flags; unit
  tests of the registry. -- `feat(sources): define cutoff sources, their events and the devices`
- [ ] T004 [US5] `SourceRunner`: restart 1-30 s, reset after 30 s, state flow, invalid events counted
  and the last error kept. Unit tests under virtual time.
  -- `feat(sources): restart a failing source after a growing pause`
- [ ] T005 [US1] Settings: cutoff source, beam device, chips device, dead time (`Settings`,
  `SettingsStore`, `SettingsScreen`), strings in three languages; unit and instrumented tests.
  -- `feat(sources): choose the cutoff source and its device in the settings`
- [ ] T006 [US1, US5] `SourceController` started and stopped by `RaceService`; source line and
  test warning on the main screen; strings. Unit tests for the controller, instrumented test that
  a hand cutoff records while a failing source restarts.
  -- `feat(sources): run the chosen source while timing mode is on and show its state`

## Phase 3: beam (US4)

- [ ] T007 [US4] `TestBeamSource`, `BeamRepository` (pending rows, raw `beam_breaks.txt`, assign the
  oldest on Cutoff, discard with confirmation, DSQ at the tap time); pending list and Cutoff label on
  the main screen; strings. Unit and instrumented tests.
  -- `feat(beam): queue beam breaks until the referee gives each a number`

## Phase 4: chips (US2, US3, US6)

- [ ] T008 [US3] `ChipMap` from `start.txt` (`StartProtocolFile.read`), read at start-up and on
  Reload; conflicts, lines that bind nothing; chips line on the main screen; `ChipsScreen` with
  conflicts and Reload; strings. Unit tests. -- `feat(chips): map chips to bibs from start.txt`
- [ ] T009 [US2, US6] `PassTracker`, `ChipRepository.apply()` (reads, passes, cutoffs in one
  transaction; raw `chip_reads.txt` after), dead time, `TestChipsSource`; every case of
  `chip-pass-vectors.json`; restart within the dead time makes no second cutoff; a deleted chip
  cutoff stays deleted; a burst of 10,000 reads is stored in order with none lost (SC-006).
  -- `feat(chips): turn chip reads into cutoffs with a dead time`
- [ ] T010 [US3] Unknown chips in `ChipsScreen`; on a new map, reads of newly known chips become
  cutoffs in time order. Unit tests from the vectors, instrumented test of the list.
  -- `feat(chips): list unknown chips and time them once the map knows them`
- [ ] T011 [US3] `StartListClient` and "Download start protocol": backup, atomic replace, map
  rebuilt, retro cutoffs, summary; failures change nothing. MockWebServer tests, including that
  starting the app, timing mode and a source make no request (SC-005); instrumented test of the
  button. -- `feat(site): download start.txt from the site by a button`

## Phase 5: new competition and documentation

- [ ] T012 "New competition" clears pending breaks, chip reads, passes and attributed chips, and
  moves `start.txt` and the raw files to `backup/`. Unit tests.
  -- `feat(cutoffs): start a new competition without the last one's chips and breaks`
- [ ] T013 README (features, using the app, data files), `docs/server-protocol.md` (the download),
  `docs/state-persistence.md` (new tables). -- `docs: describe cutoff sources, chip files and the start protocol download`

## Dependencies

T001 -> T002 -> T003 -> T004 -> T005 -> T006. T006 -> T007. T006 -> T008 -> T009 -> T010 -> T011.
T012 after T009 and T007. T013 last.
