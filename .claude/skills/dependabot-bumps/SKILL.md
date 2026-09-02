---
name: dependabot-bumps
description: Playbook for Dependabot Gradle PRs in ChronometerAndroid - regenerate the dependency lockfile, tell a compatible bump from the incompatible "2026 AndroidX train", cap versions and add ignores, and drive the PR green. Use for any Dependabot PR, a lockfile mismatch (checkDebugAarMetadata / generateDebugUnitTestLintModel / "Could not resolve ... {strictly X}"), or any AGP / Gradle-wrapper / Kotlin / KSP / compose / lifecycle / androidx bump.
---

# Handling Dependabot Gradle bumps

The project uses a Gradle **version catalog** (`gradle/libs.versions.toml`) plus
**dependency locking** (`app/gradle.lockfile`, four classpaths: debug/release runtime
and unit-test runtime). Dependabot bumps the catalog but **never runs `--write-locks`**,
so almost every Gradle PR first fails on a lock mismatch. Most red Dependabot PRs are
just the stale lockfile, not a real incompatibility.

## The loop

1. Check out the PR branch, rebased on `main`.
2. Regenerate the lockfile:
   `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"` then
   `./gradlew :app:dependencies --write-locks`.
3. Run the full gate:
   `./gradlew :app:ktlintCheck :app:detekt :app:testDebugUnitTest :app:koverVerify :app:assembleDebug :app:assembleRelease`.
   Also `./gradlew :app:generateDebugAndroidTestLintModel` - it resolves the androidTest
   classpath and is the canary for the 2026 train (concurrent-futures conflict below).
4. If everything passes, commit the regenerated lockfile (and the bump) and drive CI
   green. Done.

## When a bump is the incompatible "2026 train"

Symptoms: `checkDebugAarMetadata` demands "compileSdk of at least 37" or "AGP 9.1.0 or
higher"; or the androidTest classpath breaks with
`androidx.concurrent:concurrent-futures:{strictly 1.1.0}` vs espresso's `1.2.0` (AGP
"consistent resolution"); or lifecycle jumps to 2.9.x; or a kotlinx-serialization
`AbstractMethodError` appears in instrumented tests.

Do **not** raise compileSdk/AGP/Gradle/Kotlin to satisfy it - that breaks detekt and
CodeQL (see the root `CLAUDE.md`). Instead **cap and ignore**:

1. Find the highest compatible version empirically - for each candidate, edit the
   catalog value, `--write-locks`, and check the resolved versions +
   `generateDebugAndroidTestLintModel`. (Testing without regenerating the lock gives
   wrong results - the lockfile pins the resolution.) Known good caps that live in the
   catalog today: core-ktx 1.18.0, activity-compose 1.11.0, composeBom 2025.07.00,
   lifecycle 2.8.7.
2. Set the cap in `gradle/libs.versions.toml` with a comment explaining why.
3. Add a matching ignore in `.github/dependabot.yml`, e.g.
   `- dependency-name: "androidx.compose:compose-bom"` / `versions: [">=2025.08"]`.
   Kotlin is driven by **two** plugin ids that share the `kotlin` catalog version -
   ignore both `org.jetbrains.kotlin.android` and `org.jetbrains.kotlin.plugin.compose`
   (plus the `org.jetbrains.kotlin:*` maven glob), all `>=2.3`.
4. Regenerate the lockfile at the cap, run the gate, commit.

## The Gradle-wrapper trap

Dependabot also bumps the **Gradle wrapper** (shown as `Updates gradle-wrapper ...`).
Gradle 9 is incompatible with AGP 8.13, so revert the wrapper to 8.14.x
(`git checkout origin/main -- gradle/wrapper gradlew gradlew.bat`). Separately, the
wrapper bump commits `gradlew.bat` with a **CRLF blob** that violates the repo's
`* text=auto eol=lf` policy, so Git reports it permanently modified - this blocks
`git checkout` locally and breaks the osv-scan base checkout. `.gitattributes` marks
`gradlew.bat -text` to neutralize that; keep it.

## Group PRs and mechanics

Dependabot groups all Gradle bumps into one PR (`gradle-dependencies`). A group can mix
safe bumps with 2026-train ones - keep the safe ones, revert the incompatible ones,
regenerate the lock, add ignores. If a branch is stale (predates recent ignores), reset
it to current `main` and re-apply the fixes rather than merging stale config.

Push with your own credentials (a GITHUB_TOKEN push won't re-trigger CI). Commits are
single-line; rebase-merge when green. Dependabot cannot verify build compatibility -
grouping + ignores are the only levers to reduce noise.
