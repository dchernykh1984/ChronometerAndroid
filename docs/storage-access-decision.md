# Storage access: all-files access (not SAF)

## Status

Accepted. Deliberately chosen with the product owner; supersedes the earlier
requirement note that suggested the Storage Access Framework (SAF).

## Context

The chronometer must write two things into a **user-chosen folder in shared
storage**:

- `results.txt` — the exact input format consumed, unchanged, by the existing
  desktop referee tools (`WindowsChronometerPython`, `StartProtocolMakerPython`);
- `backup/<millis>.txt` — an immutable snapshot per press.

Referees copy/inspect these files with a file manager or a cable on site, so the
files must live at a **plain, predictable filesystem path** (e.g.
`/sdcard/android_chronometer/results.txt`) rather than behind an opaque SAF
`content://` tree URI.

## Decision

Use **all-files access** and a free-text folder path:

- API 30+ — `MANAGE_EXTERNAL_STORAGE`, granted from the system "All files
  access" screen;
- API 24–29 — the runtime `WRITE_EXTERNAL_STORAGE` permission.

Writes go through `java.io.File` with an atomic temp-file + rename
(`BackupWriter`), which is simpler and more robust for the referee workflow than
`ContentResolver`/`DocumentFile` over SAF.

## Consequences / trade-offs

- The user must grant a broad permission once. `SettingsScreen` detects a
  missing/revoked grant (`StoragePermission.isGranted`) and shows a "grant"
  button; recording never depends on it — a failed file write is surfaced as a
  non-blocking diagnostic while the cutoff stays durable in Room.
- `MANAGE_EXTERNAL_STORAGE` requires a Play Store declaration if the app is ever
  published there. This app is side-loaded for referees, so that is acceptable.

## Revoked / absent permission

Covered by `StoragePermissionTest` (grant → detected, deny/revoke → not
detected) and by `BackupWriter` returning `false` when the folder is not
writable, which lights up the on-screen backup diagnostic without disrupting
timing.
