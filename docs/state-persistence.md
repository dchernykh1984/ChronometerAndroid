# State persistence across minimize / close

Nothing the referee enters is lost when the app is minimized and reopened, or
even fully killed and relaunched. Each piece of state has a durable home:

| State | Where it lives | Survives minimize | Survives process death |
| --- | --- | --- | --- |
| Recorded cutoffs | Room (`cutoffs` table, on disk) | yes | yes - reloaded from Room |
| Settings (url, token, point, folder, input type, finish mode, theme) | SharedPreferences | yes | yes - reloaded from prefs |
| Number being typed | `rememberSaveable` in `MainScreen` | yes | yes - restored from saved instance state |
| Settings-vs-main navigation | `rememberSaveable` in `MainActivity` | yes | yes |

- The on-screen log is driven by a Room `Flow`, so it always reflects the stored
  rows - a restart simply re-reads them.
- The `ChronometerViewModel` survives configuration changes (rotation) by design;
  on real process death the values above come back from Room / SharedPreferences.

## Tests

- `CutoffPersistenceTest` - cutoffs written to a file-backed Room DB are still
  there after the database is closed and reopened (a stand-in for a restart).
- `SettingsStoreTest` - every setting round-trips through a second
  `SettingsStore` instance, i.e. it is re-read after a restart.
