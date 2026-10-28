<!--
Sync Impact Report
- Version: none -> 1.0.0 (first constitution, written with the event-sources feature of
  ChronometerPython issue #49).
- Principles: I is the maintainer's decision of 2026-10-28 (timing programs only produce
  crossings). II-V come from tmp/requirements/requirements.md, docs/ (server protocol, state
  persistence, storage access) and CLAUDE.md; VI-VIII from CLAUDE.md, AGENTS.md and the skills
  under .claude/skills and .agents/skills, which stay the detailed runtime guidance.
- Templates: plan-template.md's "Constitution Check" gate reads these principles; no template
  needed changes.
-->

# ChronometerAndroid Constitution

ChronometerAndroid is the referee's phone chronometer for control points, start and finish: it
records cutoffs without a network, keeps them in plain files the desktop referee tools read, and
forwards them to the cycling site when the network returns.

## Core Principles

### I. Cutoffs only

The app produces cutoffs -- `number#time#event#` lines -- by hand, from a light beam or from timing
chips, and nothing else. It does not count laps, rank riders or build a protocol:
FinishProtocolGeneratorPython alone does that (maintainer, 2026-10-28).

### II. Durable first

Every cutoff is stored in Room before anything else happens to it; the files and the upload follow
and never decide whether a cutoff exists. A change or a deletion first writes an immutable snapshot
of the list as it was, and is refused if that snapshot cannot be written.

### III. Offline first

Everything the referee needs works without a network. Uploads are whole snapshots with an
increasing `client_revision`, sent by WorkManager when connectivity allows. Downloads -- the start
protocol -- happen only when the referee presses a button, never on their own.

### IV. Nothing stops the referee

A failing device, connector or upload is reported on screen and never blocks recording; hand
recording keeps working whatever a source does. During a race a foreground service keeps the app
alive.

### V. Formats are a shared contract

`results.txt` holds `number#time#event#` lines, the time as `D H:M:S.mmm` in local standard time,
read by the desktop tools, PhotoFinish and the site exactly as the desktop chronometer writes them.
Start protocol lines are written by StartProtocolMakerPython and read here by field index. The
connector event protocol is the one ChronometerPython documents. A change to any of these is a
change for every tool in the set.

### VI. Three languages

Every string the referee reads exists in English, Russian and Kazakh (`values`, `values-ru`,
`values-kk`). Recorded tokens (`nextLap`, `finish`, `DSQ`) are data and stay unlocalised.

### VII. The pinned toolchain stays pinned

AGP, Kotlin, compileSdk, Compose and lifecycle stay on the versions `gradle/libs.versions.toml`
pins, because detekt and CodeQL gate CI; a dependency that needs the next train is capped, not
bumped.

### VIII. Proven by tests, plainly recorded

A behaviour change ships with unit tests (`app/src/test`) and, for what the referee does on screen,
instrumented tests on the phone and tablet profiles. Tracked files are ASCII (Russian and Kazakh
live only in the XML string resources). Commits are one-line Conventional Commits with no trailers
and no AI credit; every change goes through a pull request.

## Development Workflow

- A feature that changes a file format, the site exchange or what the referee sees gets a
  specification in `specs/<NNN>-<short-name>/` made with the Spec Kit skills, reviewed by the
  maintainer before implementation.
- `README.md` and `docs/` describe the app as it works today; a change that alters described
  behaviour updates them in the same pull request.

## Governance

This constitution states the principles; `CLAUDE.md`, `AGENTS.md` and the skills they list hold the
detail and are kept consistent with it. The maintainer amends it through a pull request that bumps
the version -- MAJOR for a principle removed or redefined, MINOR for one added or materially
expanded, PATCH for wording -- and updates the Last Amended date.

**Version**: 1.0.0 | **Ratified**: 2026-10-28 | **Last Amended**: 2026-10-28
