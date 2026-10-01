---
name: instrumented-tests
description: Run and debug the Compose UI instrumented tests (app/src/androidTest, mainly MainFlowTest) - JDK 17, connectedDebugAndroidTest, targeting one emulator with ANDROID_SERIAL, 10 parallel workers, booting AVDs headless, reproducing the CI tablet in landscape, reading CI failure logs/artifacts, and the slow-tablet flakiness patterns. Use whenever a connected/instrumented job fails or flakes, or before pushing a change to the UI.
---

# Instrumented (Compose UI) tests

CI runs `connectedDebugAndroidTest` on two profiles: a **phone** (pixel_6, API 34) and a
**tablet** (pixel_c, API 30). The tablet is markedly slower and - importantly - the app
**locks phones to portrait but leaves tablets unrestricted**, so the tablet runs in
**landscape**. Most flakes come from those two facts.

## Running locally

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_SERIAL=emulator-5560          # target ONE device (AGP honours this)
./gradlew :app:connectedDebugAndroidTest --max-workers=10 --parallel \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dchernykh.chronometer.MainFlowTest#someTest,...
```

Run the whole class by dropping `#someTest`. The maintainer wants local runs in **10
threads** (`--max-workers=10 --parallel`).

## Managing emulators

Boot an AVD headless and wait for it, then target it by serial:

```bash
EMU=~/Library/Android/sdk/emulator/emulator; ADB=~/Library/Android/sdk/platform-tools/adb
nohup $EMU -avd <name> -no-window -no-snapshot -no-audio -gpu swiftshader_indirect -port 5560 >/tmp/emu.log 2>&1 &
$ADB -s emulator-5560 wait-for-device
until [ "$($ADB -s emulator-5560 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
$ADB -s emulator-5560 shell input keyevent 82   # dismiss keyguard
# ... run tests ...
$ADB -s emulator-5560 emu kill                   # clean up only the ones you booted
```

Leave other people's running emulators (e.g. a Wear OS watch) alone.

## Reproduce the CI tablet locally (landscape, large screen)

The phone AVD is portrait-locked by the app, so it cannot reproduce landscape bugs.
Create a **tablet** AVD (smallestWidthDp >= 600 -> app runs unrestricted, defaults to
landscape) from any installed system image:

```bash
echo no | ~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n TabletTest -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d "Nexus 10" --force
```

The exact API need not match the CI tablet - the landscape + large-screen behaviour is
what reproduces the failures.

## Reading a CI failure

`gh run view <run-id> --log-failed` shows the failing test names but truncates the stack
to the Compose frame. For the exact caller line, download the report artifact:

```bash
gh api repos/<owner>/<repo>/actions/runs/<run-id>/artifacts --jq '.artifacts[]|"\(.id)\t\(.name)"'
gh api repos/<owner>/<repo>/actions/artifacts/<id>/zip > r.zip && unzip -q r.zip
# open debug/com.dchernykh.chronometer.MainFlowTest.html - it has MainFlowTest.kt:<line>
```

## Flakiness patterns (and how the suite guards against them)

- **Slow-tablet dropped taps.** A single `performClick()` can be swallowed. Never tap
  once for a state change - tap in a `waitUntil` loop until the target state holds. The
  suite has `setToggle` (switches), `selectRadio` (radios), `recordAndAwait` (record
  buttons), and `enterEditMode`/`tapUntilEditClosed` (edit rows). Add new interactions
  the same way.
- **Landscape keyboard covers buttons.** After focusing a text field, the soft keyboard
  (tall in landscape) can push buttons in the log below the fold. Scroll the target into
  the log's viewport first: `onNodeWithTag("cutoffLog").performScrollToNode(hasTestTag(tag))`
  (the LazyColumn sits above the keyboard thanks to `windowSoftInputMode=adjustResize`).
- **Do NOT use Espresso in these Compose tests.** `Espresso.closeSoftKeyboard()` waits on
  Android root-view focus and throws `RootViewWithoutFocusException` on a busy emulator -
  it took down all the edit tests on the phone once. Scrolling into view is enough; no
  keyboard dismiss needed.
- **Async recording.** A cutoff is Room-insert -> Flow -> recomposition, so assert with
  `waitUntil`/`waitForText` on the row appearing, not `waitForIdle`.
- Tests are layout-agnostic on purpose so the same suite runs on both profiles.
- A run can also fail on pure GitHub-Actions infra (runner `startup_failure`, jobs stuck
  `queued`, "adb failed with exit code 1" at boot). That is not a code bug - re-run.
