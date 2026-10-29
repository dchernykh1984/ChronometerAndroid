# Feature Specification: Cutoff sources -- by hand, light beam and timing chips

**Feature Branch**: `feat/event-sources`

**Created**: 2026-10-29

**Status**: Draft

**Input**: ChronometerPython issue [#49](https://github.com/dchernykh1984/ChronometerPython/issues/49),
comments 1-8, and the desktop chronometer's specification of the same feature (ChronometerPython
`specs/001-event-sources/spec.md`), whose chip and start protocol rules this one shares.
Maintainer's decisions of 2026-10-28:

- One app with modes: by hand, light beam (USB or Bluetooth) and timing chips.
- In this round the phone gets the modes, stubs for the USB beam, the Bluetooth beam and the chip
  readers, and test sources to try the modes with. No real device is connected yet.
- Timing programs only produce cutoffs; laps and protocols are FinishProtocolGenerator's job.
- The bib-to-chip binding comes from the start protocol (field 11, written by StartProtocolMaker).
  The phone reads it from a start protocol file in its data folder; a button downloads into that
  file the start list StartProtocolMaker uploaded to the site, before the start, never on its own.
- Chip reads of one bib that follow each other within the dead time are one cutoff: a new cutoff
  needs 5 seconds of silence from all of the bib's chips (configurable).
- Every chip read is kept in a raw file, so it can be checked afterwards how many chips worked.
- In any mode the referee can quickly record, edit or delete a cutoff; DSQ stays a hand entry.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The referee picks a source; the hand keeps working (Priority: P1)

In the settings the referee chooses where cutoffs come from -- by hand, light beam or chips -- and,
for the beam and chips, the device. While timing mode is on, the chosen device runs and the main
screen shows which one and its state. Typing a number and tapping Cutoff records a cutoff in every
mode, as today. The real devices are listed but marked "not available yet"; the test beam and test
chips work, and the main screen says so while one of them is chosen, so a test is never mistaken
for the race.

**Why this priority**: Every other story lives inside the source choice, and the choice must never
take hand recording away.

**Independent Test**: Choose chips with the test chips source and turn timing mode on: the main
screen shows the source running and a test warning, and a hand cutoff still records at once. Choose
by hand: no source runs.

**Acceptance Scenarios**:

1. **Given** by hand (the default, also right after an update), **Then** the app behaves exactly
   as `README.md` and `docs/` describe.
2. **Given** beam or chips with a device chosen, **When** timing mode is turned on, **Then** the
   device's source starts; **When** timing mode is turned off, **Then** it stops.
3. **Given** a stub device chosen, **Then** it is shown as "not available yet" and nothing starts.
4. **Given** a test source chosen, **Then** the main screen says that its cutoffs are test data.
5. **Given** the source or device changes while timing mode is on, **Then** the old source stops,
   the new one starts, and nothing recorded is lost.

---

### User Story 2 - Chip reads become cutoffs (Priority: P1)

With chips and a start protocol, every rider who passes the antennas gets one cutoff,
`number#time#event#`, stored and uploaded like a hand cutoff, with the event the finish-mode
setting gives. A rider usually carries two or three chips and each is read many times; all reads
of one bib's chips that follow each other within the dead time are one cutoff, timed at the first
read. Every read, of known and unknown chips alike, is appended to the raw reads file.

**Why this priority**: It is what chip timing is for; everything else supports it.

**Independent Test**: With a start protocol that binds chips A and B to bib 15, the test source
reports reads at 0.0 s (A), 0.3 s (B), 2.0 s (A) and 9.0 s (A): the cutoffs are 15 at the time of
the 0.0 s read and 15 at the time of the 9.0 s read, and the raw reads file has four lines. The
same sequence gives the same cutoffs on the desktop chronometer.

**Acceptance Scenarios**:

1. **Given** chips A and B bound to bib 15, **When** reads arrive at 0.0 s (A), 0.3 s (B) and
   2.0 s (A), **Then** there is one cutoff for 15, at the time of the 0.0 s read.
2. **Given** that cutoff, **When** A is read again 7 s after the last read, **Then** a second
   cutoff for 15 is recorded.
3. **Given** a rider standing at the antennas, read every second for a minute, **Then** there is
   one cutoff.
4. **Given** the dead time set to 10 s, **Then** the 10 s rule replaces the 5 s one.
5. **Given** finish mode on, **Then** chip cutoffs carry `finish`, as hand cutoffs do.
6. **Given** any read, known or unknown, **Then** the raw reads file gets one line with the chip
   id, the time and what the device reported (device time, antenna, signal strength, reader).
7. **Given** a pass whose first read made a cutoff, **When** the app is killed and restarted and
   the next read of that pass arrives within the dead time, **Then** no second cutoff is made.

---

### User Story 3 - The start protocol: a file, refreshed from the site by a button (Priority: P1)

Before the start, while there is a network, the referee presses "Download start protocol" in the
settings. The app fetches the start list StartProtocolMaker uploaded to the site, saves it as
`start.txt` in the data folder and rebuilds the map from chip to bib. In the forest nothing is
downloaded: the file is already there. A chip bound to two bibs is reported and left out. A chip
that is not in the map is listed as unknown. When the map changes, the earlier reads of chips that
just became known turn into their cutoffs.

**Why this priority**: Without the map no read can become a cutoff, and a desk that forgot a chip
must not cost a rider their cutoffs.

**Independent Test**: The test source reads unknown chip C at 10:00:00; then a downloaded start
protocol binds C to bib 22: a cutoff `22#<10:00:00 read time>#nextLap#` is recorded.

**Acceptance Scenarios**:

1. **Given** the site URL, the token and an uploaded start list, **When** the referee presses
   "Download start protocol", **Then** `start.txt` holds the uploaded lines, the previous file is
   kept in `backup/`, and the referee sees how many bibs and chips were loaded and which chips
   conflict.
2. **Given** no network, an HTTP error, an invalid response or a start list with no lines,
   **When** the referee presses it, **Then** a message says why; the file and the map are
   unchanged.
3. **Given** a chip on the lines of two bibs, **Then** it is reported with both bibs and its reads
   are attributed to neither.
4. **Given** an unknown chip, **Then** it is listed with its first read, last read and number of
   reads, until a map binds it.
5. **Given** a chip that was unknown when read, **When** a later map binds it, **Then** its earlier
   reads become cutoffs by the same dead-time rule.
6. **Given** no `start.txt` or one that cannot be read, **Then** the chips status says so, every
   read is still kept, and no cutoff is made from a read.
7. **Given** the app starts, **Then** it reads `start.txt` from the data folder and never contacts
   the site on its own; "Reload start protocol" reads a file put there by hand.

---

### User Story 4 - Beam breaks wait for a number (Priority: P2)

With the light beam, every break of the beam becomes a pending time on the main screen, oldest
first. The referee types the number of the rider who crossed and taps Cutoff: the oldest pending
time gets that number and becomes a cutoff. A false break -- a bird, a spectator -- is discarded.
Every break is also appended to a raw beam file, so none is lost when it is discarded.

**Why this priority**: Time trials and downhill start and finish one rider at a time; the beam
times them better than a thumb. It is used less often than chips at the events this app serves.

**Independent Test**: Run the test beam: each break appears as a pending time; typing a number and
tapping Cutoff records the cutoff at the break's time, not at the time of the tap.

**Acceptance Scenarios**:

1. **Given** a break at 10:00:00.123 pending, **When** the referee types 15 and taps Cutoff at
   10:00:04, **Then** 15 is recorded at 10:00:00.123 with the event of the finish-mode setting, and
   the break leaves the queue.
2. **Given** two pending breaks, **Then** the oldest is used first, and the Cutoff button shows
   the time it will use.
3. **Given** no pending break, **When** the referee taps Cutoff, **Then** the cutoff gets the time
   of the tap, as by hand.
4. **Given** a pending break, **When** the referee discards it and confirms, **Then** it leaves the
   queue and stays in the raw beam file.
5. **Given** pending breaks, **When** the app is killed and restarted, **Then** they are still
   pending.
6. **Given** any break, **Then** the raw beam file gets a line with its sequence number and time.

---

### User Story 5 - A failing source never stops recording (Priority: P2)

A source that fails is started again after a growing pause; its state is on the main screen; hand
recording works throughout.

**Why this priority**: A device fault in the middle of a race must not cost the referee the race.

**Independent Test**: Make the test source fail repeatedly: the state shows it restarting after
1 s, 2 s and 4 s, and a hand cutoff recorded meanwhile is stored at once.

**Acceptance Scenarios**:

1. **Given** a running source, **When** it fails, **Then** its state says so and it starts again
   after 1 s, then 2 s, 4 s and so on up to 30 s while it keeps failing; once it has run for 30 s
   without failing, the next pause is 1 s again.
2. **Given** a source that reports something it should not (a read without a chip id, a malformed
   value), **Then** the report is ignored and the state shows the last error.
3. **Given** a failing source, **When** the referee records a cutoff by hand, **Then** it is stored
   at once.
4. **Given** a burst of thousands of reads, **Then** every read is kept and handled in order, and
   the screen keeps answering.

---

### User Story 6 - Quick corrections in every mode (Priority: P1)

The existing Edit and Delete work on every cutoff, whatever made it. A chip cutoff that the referee
deleted as false, or edited, stays as the referee left it: no later read and no map change brings
it back.

**Why this priority**: Chips and beams make mistakes no referee can prevent; without a quick fix at
the line the protocol inherits them.

**Independent Test**: Delete a chip cutoff, then let the test source read the same chip within
the dead time and download a start protocol: the cutoff does not come back.

**Acceptance Scenarios**:

1. **Given** a false chip cutoff, **When** the referee deletes it, **Then** it is removed (with
   the backups Delete makes today) and is not made again by reads of the same pass or by a map
   change.
2. **Given** a chip cutoff under the wrong bib, **When** the referee edits its number, **Then**
   that cutoff changes and no new cutoff is made for the old bib's pass.
3. **Given** a rider seen passing without a cutoff, **When** the referee records the number by
   hand and edits the time if needed, **Then** it is a cutoff like any other.
4. **Given** the backup before a change cannot be written, **Then** the change is refused, as
   today.

### Edge Cases

- Two bibs read at the same moment: each bib gets its own cutoff.
- A read that arrives late with an earlier time (a reader flushing its buffer after a reconnect):
  it joins the pass its time falls into; a pass that never had a cutoff gets one, and a cutoff
  already made is not moved. The cutoff is appended to `results.txt` after newer ones; the protocol
  generator orders crossings by time, not by line.
- The dead time changed during a race: it applies to reads from then on; passes already formed
  keep their reads and their cutoffs.
- Chip ids in the start protocol in lower case, with stray punctuation or with spaces inside (an
  EPC typed in groups of four): matched the way StartProtocolMaker normalises chip ids; a reader's
  chip id is normalised the same way.
- A start protocol line without a bib, with `NOT_PAID` or with fewer than 12 `#` separators: its
  chips are not bound.
- `start.txt` missing or unreadable on a Reload mid-race (a file being copied in): the map read
  before it stays in use, and the chips line says so. Only "New competition", or another data
  folder without a `start.txt`, leaves the chips without a map.
- A relay of three stages with bib 21 and chips `A1;B1;C1`: A1 binds to 21, B1 to 22, C1 to 23.
- Pending beam breaks when the source changes: they stay pending and visible until assigned or
  discarded.
- DSQ: always the time of the tap, never a pending break.
- A cutoff at the time of the tap while breaks are pending (the beam missed a rider and caught a
  bird): the referee discards the false breaks first; Cutoff takes a pending break whenever there
  is one, so a number never lands on the wrong time silently -- the button shows which time it
  will use.
- "New competition": clears the cutoff list as today, and also the pending breaks, the chip reads
  and the passes; moves `start.txt`, `chip_reads.txt` and `beam_breaks.txt` into `backup/`. The
  token is cleared as today, so the next competition starts without a map until its own start
  protocol is downloaded -- a map left from another event would bind chips to the wrong bibs
  without a word.
- The phone's clock: a read is timed by the phone's clock when it arrives, unless the source
  reports a time on the phone's clock; the clock is not corrected by the app.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The referee chooses the cutoff source: by hand (default), light beam or chips, and a
  device for the beam (USB beam, Bluetooth beam, test beam) and for chips (LLRP reader for Impinj
  and Zebra, RaceResult decoder, R2000 reader, test chips). The choice is a setting: it survives a
  restart and "New competition". Hand recording works in every mode.
- **FR-002**: The chosen source runs while timing mode is on and the mode is beam or chips, inside
  the foreground service of timing mode and off the main thread; turning timing mode off stops it.
  Its state is on the main screen: running, restarting, stopped, not available yet, and the last
  error.
- **FR-003**: A source reports the events of the connector protocol that ChronometerPython
  documents: a chip read carries the chip id and, when the device gives them, a time on the phone's
  clock, the device's own time, the antenna, the signal strength and the reader id; a beam break
  carries a sequence number, a time on the phone's clock if known and the device's time; a source
  also reports its status and errors. On the phone a source runs inside the app; a device added
  later may speak the same event lines over its USB or Bluetooth link.
- **FR-004**: Devices available in this round: test beam (a break every few seconds at random) and
  test chips (passes of the chips of the loaded map in turn, two to four reads each within a
  second, and now and then a chip that is not in the map; with no map loaded, only chips no map
  holds). The others are stubs: listed, marked "not available yet", never started.
- **FR-005**: A source that fails is started again after 1 s, the pause doubling to at most 30 s
  while it keeps failing and returning to 1 s after 30 s of running without a failure.
- **FR-006**: A read's or a break's time is the time on the phone's clock the source reports or,
  without one, the time the app received it, as `D H:M:S.mmm` in local standard time, like a hand
  cutoff. A reported time before 2000 (a device clock never set) or from 2100 on makes the event
  invalid, as the desktop protocol decides: it is not used, and it becomes the source's last error
  on screen.
- **FR-007**: Every chip read is appended to `chip_reads.txt` in the data folder as
  `chip#time#device time#antenna#signal#reader#`, with a field left empty when the device did not
  give it -- the format of the desktop chronometer's raw reads file -- and stored in Room. The raw
  line comes first and once, as the desktop writes it, so a read Room cannot take is still in the
  file; a cutoff exists only once Room has it. A raw-file write that fails shows the file warning
  the app shows today; the read still counts.
- **FR-008**: The chip map is built from field 0 (bib) and field 11 (chip ids) of `start.txt`.
  Field 11 is split on commas and semicolons; each id there, and each id a source reports, is
  compared upper case with everything but ASCII letters and digits dropped, as StartProtocolMaker
  normalises it, so an EPC typed in groups of four is one id. A chip on the lines of two bibs is
  reported and not mapped. A line without a bib, with `NOT_PAID` or without field 11 binds nothing.
  Semicolons separate the riders of a team: on a line with N > 1 stages (field 4) and a numeric
  bib, the k-th rider (from 0) binds to bib + k, as FinishProtocolGenerator and the site number
  stage riders, and riders past the N-th bind nothing; any other line binds every chip to its bib.
  The file is read as UTF-8, falling back to cp1251 as the desktop chronometer does.
- **FR-009**: "Download start protocol" in the settings fetches
  `GET <site URL>/api/v1/start-list/?competition_token=<token>` only when pressed, and takes the
  lines of every device in the order the site returns them. On success it copies the previous
  `start.txt` to `backup/` without overwriting an earlier copy, replaces `start.txt` in one step,
  rebuilds the map and shows how many bibs and chips were loaded and which chips conflict. On a
  network error, an HTTP error, an invalid response or a list with no lines, nothing changes and
  the referee sees why. The answer is invalid when `devices` is not a list or a line is anything
  but text on one line (none of the line breaks Python's `str.splitlines()` knows, at which
  `start.txt` is read back); blank lines are no riders,
  so an answer of blank lines is a list with no lines. "Reload start protocol" reads `start.txt`
  again.
- **FR-010**: A bib's chip reads are grouped into passes by their read times, whatever order they
  arrive in: reads less than the dead time apart are one pass. A pass that has never had a cutoff
  gets one as soon as it forms, timed at its first read; a cutoff already made is never moved,
  even when a late read turns out earlier, and a pass that once had a cutoff never gets another,
  even after the referee edits or deletes it. Dead time: 5 s by default, settable from 1 s to
  120 s; a change applies to reads from then on.
- **FR-011**: Reads of unknown chips are listed with first read, last read and count. When the map
  changes, the reads of chips that became known join their bib's reads; every pass of the bib (by
  FR-010) that has never had a cutoff gets one, at its first read. A chip that moves from one bib
  to another keeps its past cutoffs on the old bib.
- **FR-012**: A cutoff made from chips or a beam break goes through the same durable path as a hand
  cutoff: Room first, then `results.txt` and a backup snapshot, then the upload when uploads are on.
- **FR-013**: Every beam break is appended to `beam_breaks.txt` in the data folder as
  `sequence#time#device time#`, first and once, then stored in Room as pending. While breaks are pending, Cutoff gives the typed
  number to the oldest one, at its time, with the event of the finish-mode setting, and the button
  shows that time. A pending break can be discarded after a confirmation; it stays in the raw file.
  DSQ always takes the time of the tap.
- **FR-014**: Edit and Delete work on every cutoff as today, whatever made it.
- **FR-015**: In chips mode the main screen shows the source state, the start protocol's bib and
  chip counts, the number of conflicts and the number of unknown chips; tapping it opens a chips
  screen listing conflicts and unknown chips, with "Reload start protocol".
- **FR-016**: "New competition" also clears the pending breaks, the chip reads and the passes, and
  moves `start.txt`, `chip_reads.txt` and `beam_breaks.txt` into `backup/`, keeping them.
- **FR-017**: Every new string the referee reads exists in English, Russian and Kazakh. The source
  and device names are strings; recorded tokens stay unlocalised.
- **FR-018**: No permission is added in this round. `README.md` and `docs/` describe the sources,
  the new files and the download.

### Key Entities

- **Source**: the device behind a mode; has a kind (beam or chips), a state and the last error.
- **Chip read**: chip id, time, device time, antenna, signal strength, reader.
- **Pass**: a bib's reads less than the dead time apart; remembers whether it has had a cutoff.
- **Beam break**: sequence number, time, device time; pending, assigned or discarded.
- **Chip map**: chip id to bib, built from `start.txt`, with conflicts and unknown chips.
- **Cutoff**: a `number#time#event#` line of `results.txt`, as today.
- **Files**: `start.txt` (the start protocol), `chip_reads.txt` and `beam_breaks.txt` (append-only
  logs of everything a device reported), in the data folder next to `results.txt`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The read sequences of Stories 2, 3 and 6 -- including a late read and a deleted
  cutoff -- produce exactly the expected cutoffs, proven by unit tests that use the same sequences
  as the desktop chronometer's tests.
- **SC-002**: A source failing repeatedly restarts on the schedule of FR-005, while a hand cutoff
  is stored at once, proven by a unit test and an instrumented test on both profiles.
- **SC-003**: Chip reads, passes, pending breaks and cutoffs survive the process being killed,
  proven by tests on a file-backed Room database; a database of 0.7.0 migrates with every cutoff
  kept, proven by the migration test.
- **SC-004**: With the source by hand, the existing unit and instrumented tests pass unchanged.
- **SC-005**: Nothing is downloaded unless the referee presses the button: starting the app,
  turning timing mode on and running a source make no request to the site, proven by tests.
- **SC-006**: A burst of 10,000 reads is stored and handled in order with none lost, and nothing
  of it runs on the main thread, proven by a unit test.
- **SC-007**: Every new string exists in the three languages, proven by the localisation test.

## Assumptions

- StartProtocolMaker writes chip ids into field 11 (its specification 002); a start protocol
  without field 11 binds no chip.
- The site's `GET /api/v1/start-list/` returns every device's uploaded lines; no site change is
  needed.
- The raw files use the desktop chronometer's formats, so tools made for its files read the
  phone's as well.
- A real USB or Bluetooth device will need its permissions and a connected-device type for the
  foreground service; that is for the round that adds one.
- Not in this round: real devices, replaying a raw file, a chip report on the phone, check-in at the
  start corral, chips sent by the site.
