# ChronometerAndroid

Offline-first Android chronometer for bike-event timing. It captures split
times without a network connection and auto-forwards them to the server once
connectivity returns.

## Features

- Record split times ("cutoffs") offline with a big, hard-to-miss **Cutoff**
  button; a small secondary button records a **DSQ** (disqualification). Every
  cutoff is written to a local Room database first, so nothing is lost on a
  crash, and the log auto-scrolls to the newest entry.
- **Finish mode** records `finish` instead of `nextLap`, and the participant
  number field accepts digits (default) or free text.
- **Edit** corrects a cutoff's number/time or offers **Delete** with confirmation.
  Deletion requires a backup of the original list and writes another snapshot
  after updating it; existing backups are kept.
- Mirrors each press to plain files in a user-chosen folder: `results.txt` (the
  format the desktop referee tools consume) plus an immutable `backup/<id>.txt`
  snapshot per press, written atomically.
- Optionally uploads the accumulated snapshot to a server over HTTP(S) via
  WorkManager when connectivity is available; turning uploads on also flushes
  cutoffs recorded while offline. See
  [docs/server-protocol.md](docs/server-protocol.md).
- **Timing mode**: a foreground service with an ongoing notification keeps the
  app alive during a long race; while it is on, the screen stays awake and the
  app suggests enabling Do Not Disturb.
- **Cutoff sources**: by hand (the default), a light beam or timing chips, each
  with its device. This release runs the test beam and the test chips; the USB
  and Bluetooth beams and the LLRP (Impinj, Zebra), RaceResult and R2000 readers
  are listed as "not available yet". A source runs while timing mode is on,
  starts again after a growing pause (1 s up to 30 s) when it fails, and shows
  its state on the main screen. Recording by hand works in every mode.
- **Light beam**: every break waits on the main screen, oldest first. Type the
  rider's number and tap **Cutoff**: the oldest break gets it, at the break's
  time, which the button shows ("Cutoff 10:00:00.123"). A false break is
  discarded after a confirmation; DSQ always takes the time of the tap.
- **Timing chips**: `start.txt` in the data folder maps chips to bibs (field 11
  of the StartProtocolMaker start protocol; a relay's riders bind to bib + k).
  A bib's reads less than the dead time apart (5 s by default, 1-120 s) are one
  pass, and each pass makes one cutoff at its first read; a cutoff the referee
  deleted or edited is never made again. Chips the map does not hold are listed
  until a map binds them, and then their earlier reads become cutoffs.
- UI localized in **English, Russian and Kazakh** (switchable in settings,
  defaults to the system language); light / dark / system theme; and a "New
  competition" reset. Settings, cutoffs and in-progress input survive minimize
  and restart.

## Setup

### 1. Download the project

Install Git if you don't have it:

- **macOS:** `brew install git`
- **Linux (Ubuntu / Debian):** `sudo apt install git`
- **Windows:** download from [git-scm.com](https://git-scm.com/downloads) and run the installer

Then clone the repository:

```bash
git clone https://github.com/dchernykh1984/ChronometerAndroid.git
cd ChronometerAndroid
```

All subsequent commands should be run from the `ChronometerAndroid` folder.

### 2. Install a JDK 17

The Android Gradle Plugin used here targets Java 17.

- **macOS:** `brew install --cask temurin@17`
- **Linux (Ubuntu / Debian):** `sudo apt install openjdk-17-jdk`
- **Windows:** download **Temurin 17** from [adoptium.net](https://adoptium.net/) and run the installer

Verify it is the active JDK:

```bash
java -version
```

The output should report version `17`.

### 3. Install the Android SDK

**Easiest (recommended): Android Studio.** It bundles the SDK, an emulator, and
device tooling.

- **macOS:** `brew install --cask android-studio`
- **Others:** download from [developer.android.com/studio](https://developer.android.com/studio)

On first launch Android Studio installs the SDK and points the project at it
(it writes `local.properties` for you).

**Command-line only (no IDE):**

```bash
# macOS
brew install --cask android-commandlinetools

# Install the pieces this project needs, then accept the licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sdkmanager --licenses
```

Point the build at the SDK by exporting `ANDROID_HOME` (and adding
`platform-tools` to your `PATH`), or by creating a `local.properties` file with:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

> **Gradle itself does not need to be installed.** The committed wrapper
> (`./gradlew`) downloads the correct Gradle version automatically.

### 4. Build and test

```bash
./gradlew assembleDebug      # build a debug APK
./gradlew testDebugUnitTest  # run the JVM unit tests
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### 5. Run and debug the app

- **Android Studio:** open the project, pick an emulator or a USB-connected
  device (with USB debugging enabled) and press **Run**.
- **Command line:** install on a connected device and launch it:

  ```bash
  ./gradlew installDebug
  adb shell am start -n com.dchernykh.chronometer/.MainActivity
  ```

### 6. Set up pre-commit hooks (contributors)

Install [pre-commit](https://pre-commit.com/) (`brew install pre-commit`, or
`pipx install pre-commit`), then register the hooks:

```bash
pre-commit install
pre-commit install --hook-type commit-msg
pre-commit install --hook-type pre-push
```

After that the hooks run automatically:

- **on commit** - file formatting, YAML/TOML checks, and a non-ASCII guard;
- **on the commit message** - Conventional Commits validation (commitizen);
- **on push** - `ktlintCheck` and `detekt` (these need the JDK + Android SDK).

To run all checks manually across every file:

```bash
pre-commit run --all-files
```

## Using the app

Open **Settings** (top bar) to configure the site URL and upload token, the
control-point number, the data folder, the number input type, finish mode, theme
and language, and to grant file access. Recording works without any of this;
uploads require a URL and a token.

On the main screen, **Enable timing mode** starts the foreground service (keeping
the app alive and the screen awake for the race) and reminds you to turn on Do
Not Disturb; **Finish timing mode** stops it. Type a number and tap **Cutoff**
(or press Enter); the small button records a **DSQ**.

**Cutoff source** in the settings picks by hand, the light beam or chips and
the device; a test device marks its cutoffs as test data on the main screen.
With chips, the settings also hold the dead time and **Download start
protocol**: while there is a network, it saves the settings, fetches the start
list StartProtocolMaker uploaded to the site and replaces `start.txt` with it
(see [docs/server-protocol.md](docs/server-protocol.md)). Nothing else asks the
site for it; in the forest the file already there is used. To use a
`start.txt` copied by hand, tap the chips line on the main screen and
**Reload start protocol**; the chips screen also lists the chips on two bibs
and the unknown chips.

### Permissions

- **All-files access** (`MANAGE_EXTERNAL_STORAGE` on API 30+, or
  `WRITE_EXTERNAL_STORAGE` on API 24-29) to write into the chosen shared-storage
  folder. This is a deliberate choice over SAF - see
  [docs/storage-access-decision.md](docs/storage-access-decision.md).
- **Notifications** (`POST_NOTIFICATIONS`, API 33+) for the timing-mode
  notification; recording still works if it is denied.
- Foreground-service permissions for timing mode, and `INTERNET` for uploads.

### Data files

In the configured folder (default `/sdcard/android_chronometer`):

- `results.txt` - the current list of cutoffs, one `number#time#event#` per line;
- `backup/<id>.txt` - one immutable snapshot per press; edits and deletions add
  `backup/<id>-N.txt` snapshots without overwriting earlier ones;
- `start.txt` - the start protocol the chip map is read from;
  `backup/start-<millis>.txt` keeps the one each download replaced;
- `chip_reads.txt` - every chip read, known or not, as
  `chip#time#device time#antenna#signal#reader#`;
- `beam_breaks.txt` - every beam break, discarded or not, as
  `sequence#time#device time#`.

The raw files use the desktop chronometer's formats, so its tools read them.

To remove a cutoff, choose **Edit**, then **Delete**, and confirm its saved number
and time. If the backup before deletion fails, the cutoff stays. If the updated
snapshot fails, the original backup remains available and the app shows a warning.
Deleting the final cutoff also sends an empty snapshot to clear this device's
stream on the site, including when uploads are enabled later.

"New competition" clears the cutoff list, the beam breaks waiting, the chip reads
and passes, the upload token and the point number, and moves `start.txt`,
`chip_reads.txt` and `beam_breaks.txt` into `backup/<name>-<millis>.txt`, so the
next competition starts without the last one's chip map. It **keeps the
backups**; settings show the data folder's size so you can clean old backups up
manually.

## Continuous integration and releases

Pull requests must pass the required checks before review: the Gradle gate
(`android` - ktlint, detekt, Android Lint, unit tests, Kover coverage, assemble),
instrumented UI tests on both a phone and a tablet emulator, CodeQL, an OSV
dependency scan, actionlint, pre-commit and commitizen.

Releases are automated. `release-please` maintains a version-bump PR from the
Conventional Commits; merging it tags a GitHub Release, and the **Build and
Distribute** workflow (called automatically) attaches a signed, attested APK -
no manual tag push required.

## Contributing

Before requesting a review, make sure the CI pipeline passes on your pull
request. Once the pipeline is green, request a review from
[@dchernykh1984](https://github.com/dchernykh1984).
