# ChronometerAndroid

Offline-first Android chronometer for bike-event timing. It captures split
times without a network connection and auto-forwards them to the server once
connectivity returns.

> **Status:** project scaffolding and engineering practices only. Application
> functionality is not implemented yet.

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
