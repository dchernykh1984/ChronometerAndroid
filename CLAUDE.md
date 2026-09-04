# ChronometerAndroid - agent guide

Offline-first Android chronometer for timing bike events (a referee/volunteer tool
for control points, start and finish). Every cutoff is written to a local Room DB
first, mirrored to `results.txt` (the format the desktop referee tools consume) plus
an immutable per-press `backup/<id>.txt`, and forwarded to a server over HTTP(S) via
WorkManager when connectivity returns. UI is Jetpack Compose, localized in English,
Russian and Kazakh.

Build/test locally with JDK 17: `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"`.

## The toolchain is deliberately pinned - do not "modernize" it

`gradle/libs.versions.toml` pins AGP 8.13.2, Kotlin 2.2.x, KSP 2.3.x, compileSdk 36,
composeBom 2025.07.00 (compose 1.8.x), lifecycle 2.8.7, and the Gradle wrapper at
8.14.x. The "2026 AndroidX train" - AGP 9 / Gradle 9 / compileSdk 37 / Kotlin >= 2.3 /
compose 1.9+ / lifecycle 2.9+ - breaks two CI gates:

- **detekt** has no stable Gradle-9 release (only 2.0.0-alpha), and AGP 8.13 cannot
  run on Gradle 9 (it uses a Gradle internal API removed in 9.6).
- **CodeQL** cannot extract Kotlin >= 2.3.30.

`.github/dependabot.yml` ignores those majors. Never bump a dependency into that
train to satisfy it - cap it at the highest compatible version and add an ignore
instead. See the **dependabot-bumps** skill.

## Commits & pull requests (strict, non-negotiable house style)

- **Single-line commit messages only.** Conventional Commits (`type(scope): summary`),
  no body, and **no trailers** - never append `Co-Authored-By`. `commitizen` enforces
  the format in CI. Allowed types: build, bump, chore, ci, docs, feat, fix, perf,
  refactor, revert, style, test.
- **PR bodies carry no attribution** - no "Generated with Claude Code", no co-authors.
- **Never commit to `main` directly.** Branch, open a PR with `gh pr create`, and drive
  CI to green. `main` is protected by a repository ruleset: 1 approving review, linear
  history, no force-push, no deletion. PRs land as **rebase merges**. Merging requires
  an approval - do not self-approve/merge unless the user explicitly asks.
- **Tracked files stay ASCII.** A `no-non-ascii` pre-commit hook rejects any byte above
  0x7F in Kotlin, YAML, Markdown, TOML, shell and JSON (the vendored `gradlew` is
  excluded as upstream code). XML is deliberately not in that list, which is why the
  Russian and Kazakh strings live in `res/values-ru/` and `res/values-kk/` and nowhere
  else - a Cyrillic character in a Kotlin string or a comment fails the commit.
- **Write files as UTF-8.** On Windows a PowerShell redirect, `Set-Content` or `Out-File`
  defaults to UTF-16, and that hook then rejects a file whose text looks perfectly plain
  in an editor: the bytes are the problem, not the characters. `file <path>` says which
  encoding you actually wrote.

## Reviewing a change

Check three things beyond correctness, because the maintainer always does:

1. New behaviour is covered by **automated tests** (unit under `app/src/test`,
   instrumented under `app/src/androidTest`).
2. Any new user-facing string is **localized in all three languages**:
   `app/src/main/res/values/strings.xml` (en), `values-ru/`, `values-kk/`. Recorded
   event tokens (e.g. `DSQ`) are data, not UI, and stay unlocalized.
3. The change respects the pinned toolchain and the commit/PR house style above.

The maintainer often asks for several review cycles that fix *all* valid findings
before merge. The built-in `/code-review` skill is available for the diff review.

## CI gates (every one must be green before merge)

`android` (ktlintCheck, detekt, lintDebug, testDebugUnitTest, koverVerify,
assembleDebug/Release), `connected` instrumented tests on a **phone** (pixel_6,
API 34) and a **tablet** (pixel_c, API 30), CodeQL `Analyze (java-kotlin)`,
`osv-scan`, `pre-commit`, `actionlint`, `commitizen`. Releases are cut by
release-please (`chore(main): release chronometer-android x.y.z`).

Take the verdict from the rollup rather than `gh pr checks`, whose per-check status lags
and can still say `pending` long after a job has finished - which reads like a hung
check on a pipeline this wide:

```bash
gh pr view <n> --json statusCheckRollup \
  --jq '[.statusCheckRollup[] | {name:(.name//.context), s:(.conclusion//.state)}]'
```

## Deeper how-tos (skills in `.claude/skills/`)

- **dependabot-bumps** - the playbook for Dependabot Gradle PRs (regenerate the
  lockfile, spot the incompatible 2026 train, cap + ignore, the Gradle-wrapper CRLF
  trap, driving the group PR green).
- **instrumented-tests** - running and debugging the Compose UI tests, managing
  emulators, reproducing the CI tablet in landscape, and the slow-tablet flakiness
  patterns.
- **website-content** - contributing app copy (description / release news) to the
  separate cycling website.
