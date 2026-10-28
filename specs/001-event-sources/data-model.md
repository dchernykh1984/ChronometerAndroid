# Data model: cutoff sources

## Settings (SharedPreferences, `SettingsStore`)

| Key | Meaning | Default |
| --- | --- | --- |
| `cutoff_source` | `HAND`, `BEAM` or `CHIPS` | `HAND` |
| `beam_device` | `TEST_BEAM`, `USB_BEAM`, `BLE_BEAM` | `TEST_BEAM` |
| `chips_device` | `TEST_CHIPS`, `LLRP`, `RACERESULT`, `R2000` | `TEST_CHIPS` |
| `dead_time_s` | 1-120 | 5 |

All survive "New competition".

## Room, version 3 (migration 2 -> 3 adds tables, `cutoffs` unchanged)

`chip_reads`

| Column | Type | Note |
| --- | --- | --- |
| `id` | INTEGER PK autoincrement | arrival order |
| `chip` | TEXT | normalised |
| `timeStr` | TEXT | `D H:M:S.mmm` |
| `timeMs` | INTEGER | `timeStr` parsed, for the pass rule |
| `deviceTime`, `antenna`, `reader` | TEXT | empty when not reported |
| `rssi` | REAL, nullable | |

`passes`

| Column | Type | Note |
| --- | --- | --- |
| `id` | INTEGER PK autoincrement | |
| `bib` | TEXT, indexed | |
| `firstMs`, `lastMs` | INTEGER | |
| `issued` | INTEGER (boolean) | a cutoff was made for it once |

`attributed_chips`: `chip` TEXT PK -- chips whose reads have been put into passes.

`pending_breaks`

| Column | Type | Note |
| --- | --- | --- |
| `id` | INTEGER PK autoincrement | arrival order; oldest first |
| `seq` | INTEGER | |
| `timeStr` | TEXT | |
| `deviceTime` | TEXT | |

## Files in the data folder

| File | Content |
| --- | --- |
| `results.txt`, `backup/<id>.txt` | unchanged |
| `start.txt` | the start protocol, as downloaded or put there by hand |
| `chip_reads.txt` | `chip#time#device time#antenna#signal#reader#`, append only |
| `beam_breaks.txt` | `sequence#time#device time#`, append only |
| `backup/start-<millis>.txt` | the previous `start.txt` before a download |
| `backup/<name>-<millis>.txt` | files moved aside by "New competition" |

The raw formats are ChronometerPython's (`specs/001-event-sources/contracts/raw-files.md` there).

## In memory

- **Chip map**: chip -> bib from `start.txt` (fields 0 and 11; a relay's k-th rider, after k
  semicolons, binds to bib + k when the line has N > 1 stages and a numeric bib), conflicts, counts.
- **Unknown chips**: from `chip_reads` of chips not in the map: first, last, count.
- **Source state**: `Stopped`, `Running`, `Restarting(inSeconds)`, `NotAvailable`, last error.
