# Research: cutoff sources on the phone

## 1. Sources run inside the app, as coroutines

- **Decision**: a source is a Kotlin `Flow<SourceEvent>`; collecting it runs the device, an
  exception is a failure. A supervisor collects the chosen source in the timing-mode service's
  scope and restarts it after 1 s, doubling to 30 s, back to 1 s after 30 s of steady running.
- **Rationale**: Android cannot run an arbitrary program as a child process the way the desktop
  does, and every device library the phone will use (USB serial, Bluetooth LE, TCP) has a Kotlin or
  Java API. The events keep the fields of the desktop's connector protocol, so a device that one
  day speaks that protocol over USB or Bluetooth is just another source.
- **Alternatives**: a separate Android process per source (rejected: binder plumbing for no gain
  while the only sources are tests and stubs); a remote service (rejected for the same reason).

## 2. The service owns the source

- **Decision**: the source runs only while timing mode is on, inside `RaceService`, through an
  app-wide controller held by `ChronometerApp`; the screen observes the controller's state.
- **Rationale**: timing mode already keeps the process alive (foreground service, screen awake);
  a source outside it would be killed in the background. One switch for the referee.

## 3. Room first, in one transaction

- **Decision**: a batch of events is applied in one Room transaction: chip reads inserted, passes
  updated, the cutoffs they make inserted, pending beam breaks inserted. After the commit the
  revision is bumped, `results.txt` and its snapshot are written and the upload is queued -- the
  path a hand cutoff takes today. The raw lines are appended before the transaction, once a batch
  however often it is tried, as the desktop writes its raw reads before timing them: a batch Room
  cannot take is still in the device's own record.
- **Rationale**: constitution II; unlike the desktop, a pass and its cutoff commit together, so a
  crash can neither lose nor repeat a chip cutoff. Batching (all events waiting in the channel,
  at most 200) keeps a burst from costing one fsync per read.

## 4. The pass rule and the shared vectors

- **Decision**: per bib, passes `[first, last]`; a read at `t` joins every pass with
  `first - dead < t < last + dead`, merging them, else starts one, and a new pass gets a cutoff
  at its first read. Times are the milliseconds of the formatted `D H:M:S.mmm` time.
  `chip-pass-vectors.json` is copied from ChronometerPython's `specs/001-event-sources/contracts/`
  into the unit-test resources and every case runs here.
- **Rationale**: one rule on both platforms, proven by the same cases (spec SC-001).

## 5. Start protocol download

- **Decision**: `StartListClient` with `HttpURLConnection` and the upload client's 10 s timeouts,
  `org.json` for the response; `GET <url>/api/v1/start-list/?competition_token=<token>`; an empty
  list is a failure. The previous `start.txt` goes to `backup/start-<millis>.txt`, the new one is
  written atomically with `BackupWriter`'s temp-file-and-move.
- **Rationale**: no new dependency; the same request FinishProtocolGenerator makes.

## 6. Beam breaks wait in Room

- **Decision**: a break is a pending row; Cutoff with pending rows takes the oldest, records the
  cutoff at its time and deletes the row in one transaction; Discard deletes it after a
  confirmation. The raw file keeps every break.
- **Rationale**: a pending time survives a restart (spec US4) and two quick taps cannot give one
  break two numbers.

## 7. Test sources

- **Decision**: test beam: a break every 5-20 s at random; test chips: a pass every 3 s of the next
  bib of the loaded map, two to four reads of its chips within a second, and every tenth pass a chip
  `TEST0000` that no map holds. Both take a `Random` and a clock so tests run them deterministically
  under virtual time. While one runs, the main screen shows that its cutoffs are test data.
- **Rationale**: the maintainer has no device yet (decision 6B of 2026-10-28); the test sources
  exercise every path a real one will.

## 8. New competition

- **Decision**: clear the pending breaks, chip reads and passes with the cutoffs;
  move `start.txt`, `chip_reads.txt` and `beam_breaks.txt` to `backup/<name>-<millis>.txt`.
- **Rationale**: spec FR-016; the token is cleared too, so the referee downloads the new event's
  start protocol anyway, and a map from another event cannot bind chips silently.
