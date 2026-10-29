# State persistence across minimize / close

Nothing the referee enters is lost when the app is minimized and reopened, or
even fully killed and relaunched. Each piece of state has a durable home:

| State | Where it lives | Survives minimize | Survives process death |
| --- | --- | --- | --- |
| Recorded cutoffs | Room (`cutoffs` table, on disk) | yes | yes - reloaded from Room |
| Settings (url, token, point, folder, input type, finish mode, theme) | SharedPreferences | yes | yes - reloaded from prefs |
| Chip reads, each with the bib it went to | Room (`chip_reads`) | yes | yes |
| Passes: a bib's reads less than the dead time apart | Room (`passes`) | yes | yes - a read after a restart joins its pass, so no second cutoff |
| Beam breaks waiting for a number | Room (`pending_breaks`) | yes | yes - still waiting, oldest first |
| Chip map | read from `start.txt` | yes | yes - read again at start-up |
| Number being typed | `rememberSaveable` in `MainScreen` | yes | yes - restored from saved instance state |
| Main, settings and chips screens | `rememberSaveable` in `MainActivity` | yes | yes |
| Last start list download's result | `ChronometerViewModel` | yes | no - the file and the map it made stay |

- The on-screen log is driven by a Room `Flow`, so it always reflects the stored
  rows - a restart simply re-reads them.
- The `ChronometerViewModel` survives configuration changes (rotation) by design;
  on real process death the values above come back from Room / SharedPreferences.

## Tests

- `CutoffPersistenceTest` - cutoffs written to a file-backed Room DB are still
  there after the database is closed and reopened (a stand-in for a restart).
- `SettingsStoreTest` - every setting round-trips through a second
  `SettingsStore` instance, i.e. it is re-read after a restart.
- `BeamRepositoryTest.pendingBreaksOutliveTheProcess` and
  `ChipRepositoryTest.aRestartWithinTheDeadTimeMakesNoSecondCutoff` - the same,
  for the beam breaks waiting and for the passes.
- `MigrationTest` - the database of an older version keeps every cutoff when it
  gains the source tables.
