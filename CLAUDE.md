# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

LibreMediaConverter is an Android media converter (Kotlin, Jetpack Compose, Material 3) with two
conversion engines: Media3 Transformer for the hardware path and FFmpeg for everything the platform
cannot do. See `@README.md` for the architecture and `@LICENSES/README.md` for the split license —
this file covers only what is not obvious from the code.

## Build, test, lint

**Everything is on Java 25** — daemon, CI, IDE, and the app's own bytecode. Four places say so and
they must not drift apart:

| Where | What sets it |
|---|---|
| Gradle daemon | `gradle/gradle-daemon-jvm.properties` → `toolchainVersion=25` |
| CI | `java-version: '25'` in both workflows |
| IDE | `.idea/misc.xml` |
| App bytecode | `compileOptions` in `app/build.gradle.kts` |

**Do not pick a JDK for the daemon — the repo does.** `gradle-daemon-jvm.properties` carries foojay
download URLs per platform, so Gradle provisions and runs the daemon on Java 25 regardless of what
`JAVA_HOME` says (that only sets the *launcher* — `./gradlew --version` prints both). Change it with
`./gradlew updateDaemonJvm --jvm-version=NN`, never by hand.

**Reaching 25 in the bytecode row took a deliberate build change.** AGP 9's built-in Kotlin compiles
with the KGP it bundles — 2.2.10 for AGP 9.3.1 — and that caps `jvmTarget` at 24. The root
`build.gradle.kts` puts KGP (and the lockstep Compose compiler plugin) on the buildscript classpath
so AGP picks up 2.4.10 instead, which supports up to 26. That is why the module applies
`com.android.application` and the Compose plugin by `id()` rather than from the catalog. Verified end
to end, not assumed: compiled classes report major version 69, D8 dexes them, and R8 minifies them.

Consequences worth knowing before touching any of it:

- Raising `kotlin` requires a matching `compose-compiler-gradle-plugin`; they are one version.
- Still **do not** apply `org.jetbrains.kotlin.android` — incompatible with AGP 9's DSL.
- Java 24 is *not* an option even though Kotlin allows it: Adoptium dropped the EOL non-LTS, so
  there is no installable temurin-24. 25 is LTS and in the repo.

The Gradle wrapper does **not** float and cannot: `distributionUrl` names one archive and
`distributionSha256Sum` is that file's checksum. Bump it with `./gradlew wrapper --gradle-version X
--gradle-distribution-sha256-sum <sha>` so the two stay consistent.

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:ktlintCheck            # formatting
./gradlew :app:ktlintFormat           # fix formatting in place
./gradlew :app:detekt                 # static analysis
./gradlew :app:lintDebug              # Android lint
./gradlew :app:jacocoTestReport       # coverage (XML+HTML under app/build/reports/jacoco/)
# single unit test:
./gradlew :app:testDebugUnitTest --tests "org.libremediaconverter.model.ConversionRouterTest"
```

CI's "Static analysis" gate is exactly `./gradlew :app:ktlintCheck :app:detekt :app:lintDebug
--continue`. Run it with `--continue` locally too: one round trip gives you all three lists instead
of the first one that fails.

**Before treating a change as done**, run: `assembleDebug` + `testDebugUnitTest` +
`compileDebugAndroidTestKotlin` + `ktlintCheck` + `detekt` + `lintDebug`.
`compileDebugAndroidTestKotlin` matters more here than it looks — the instrumented suite cannot run
on this machine (below), so without it an androidTest compile error is not discovered until CI.
ktlint and detekt also cover the `test`/`androidTest` source sets that `lintDebug` skips.

## Instrumented tests do not run locally

Two independent reasons, so do not spend time on either:

- **Emulators segfault on this host.** qemu dies on every AVD. Instrumented tests run on CI or on
  the physical Pixel, never in a local emulator.
- **The API 37 image is broken.** `android-37.0` crash-loops surfaceflinger inside its own gralloc
  mapper, so every test fails there regardless of this app. `docs/api-37-emulator-crash.md` records
  the evidence and the ruled-out fixes; CI's matrix therefore stops at API 36 even though targetSdk
  is 37. **API 37 needs a manual check on the Pixel 10 Pro XL before each release.**

On a device or emulator, build only the ABI it can execute:

```bash
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
```

FFmpeg's native libraries dominate the APK, so shipping arm64 to an x86_64 emulator doubles the
install for code that can never run — and on API 37 the full APK does not fit at all.

## Conventions

- **ktlint owns formatting, detekt owns static analysis.** detekt's formatting ruleset is off, so
  the two can never disagree about the same line. Never hand-fix a formatting complaint — run
  `ktlintFormat`. Style is `intellij_idea` at 120 columns, set in `.editorconfig`.
- **detekt config is `config/detekt/detekt.yml`**, merged onto detekt's defaults
  (`buildUponDefaultConfig = true`), so it carries only the rules this codebase legitimately
  breaks — each with the reason written next to it. Relax a rule that way or fix the code; never a
  bare `@Suppress`. Do not invent config keys: unknown ones are rejected.
- The `model` package is excluded from `ReturnCount` and `CyclomaticComplexMethod` only. It is the
  decision layer, where one branch is one documented user-visible outcome and the metric counts
  answers rather than complexity. Every other rule still applies there.
- **Coverage is reported, not gated** — **29.8% of lines (629/2113), 28.7% of branches**, measured
  on `main` 2026-08-23 with `./gradlew :app:jacocoTestReport`. A floor needs a baseline that has
  settled first, and this one has not: the figure **fell** from the ~31% recorded earlier even
  though the JVM suite went from 11 test files to 43. Main source grew 4,114 -> 5,715 lines over
  the same period, so the denominator outran the numerator. Re-measure before quoting it; do not
  assume more tests means a higher percentage here.
- **Testable code is not done until it is tested.** If a piece is unit testable, it gets unit
  tests before it counts as done. If it is e2e testable, it gets e2e tests. Both clauses apply —
  a change that is both needs both.

  Three things make that a real bar rather than a slogan here:

  - **Unit-testable is broader than it looks.** The pure-seam pattern — `work/FailureOutcome.kt`
    documents the reasoning — turns "needs a device" into "a pure function plus a thin edge".
    Robolectric is in the JVM source set, `compose-ui-test-junit4` with it, so Compose screens are
    unit testable too. Reach for the seam before concluding something cannot be unit tested.
  - **E2E is runnable locally now.** `tools/local-emulator/run-e2e.sh` runs API 33-36 on this
    machine; see `docs/local-emulator.md`. That was believed impossible until the SELinux/renderer
    cause was found, and it is what makes the e2e half of this norm enforceable.
  - **A test has to bite.** Revert the line it covers, confirm it goes red, restore. A review of
    this codebase ran 46 mutations against a 257-test suite and **9 were vacuous** — five of them
    passing the whole suite over a completely unguarded code path. Green is not evidence.

  Name what you did not cover and why. Genuine exemptions exist; implied coverage is the problem.

- `kotlin.code.style=official`. Gradle stays Kotlin DSL.

- **File one-off issues with `tools/github/file-issue.sh`, not `gh issue create`.** `gh issue
  create` does not touch the project board, so the issue exists, carries its labels, and is
  invisible in the Kanban — indistinguishable from never having been filed. Measured 2026-08-24:
  eight issues filed as a scripted batch all reached the board; one filed as a one-off minutes
  later did not. A batch carries the board step in its loop; **one-offs are where it slips**, which
  is what the script is for. It resolves the project and Status ids by name rather than caching
  them, and it **reads the item back** — a mutation returning 200 is not evidence the board shows
  what was asked for. Exit 3 means the issue was created but did not reach the board, and prints
  the number so it cannot be lost quietly.

  `above-cut` and `backlog` are **labels from the 2026-08-22 triage pass** — "worked autonomously
  overnight" and "held for manual review". They are not board columns. Status carries board state;
  do not put a cut label on a newly filed ticket.

- **shellcheck runs in CI**, inside the Static analysis job, over `git ls-files '*.sh'` so a new
  script is covered without editing the workflow. It runs at full severity, `info` included: the
  two findings that raises today are answered with targeted `disable` directives carrying their
  reason, exactly as `config/detekt/detekt.yml` carries only the rules this codebase legitimately
  breaks. Do not silence it with `--severity=warning` — that hides the next real finding too.
  **It is pinned by image digest, and joins ktlint/detekt/JaCoCo in the "Dependency versions"
  rule above** — for exactly the reason stated there, demonstrated the day it was added. The first
  cut used the runner's ambient shellcheck. That is **0.9.0**, while the container used to check
  locally was 0.11.0, and the two disagree about how to report a trap handler: 0.11.0 says
  `SC2329` once on the declaration, 0.9.0 says `SC2317` on each of seven lines in the body. Same
  script, same directive, one green and one red. Directives that must survive both name both codes.

  Locally, use the same pin rather than whatever is installed:
  `podman run --rm -v "$PWD:/mnt:z" docker.io/koalaman/shellcheck@sha256:61862eba... <files>`
  (the digest is in `status_check.yml`; there is no shellcheck system package on this host).

  **It does not cover inline `run:` blocks in the workflows**, and a good deal of this repo's bash
  lives there. `actionlint` does cover them — it runs shellcheck over each `run:` — and reports one
  pre-existing `info` finding in `build.yml`. It is not wired in because every action here is
  pinned by SHA, and actionlint's usual installer is a `curl | bash` off a moving branch; doing it
  properly means pinning a container digest. Tracked separately rather than bolted on.

## Dependency versions

Libraries **float on minor + patch** (`coreKtx = "1.+"`). Three groups deliberately do not:

- **`agp`, `kotlin`, `ksp` are version-locked to each other.** AGP 9.3.1's POM declares
  `kotlin-gradle-plugin` 2.2.10, and that is what AGP's built-in Kotlin compiles with. Android lint
  will suggest Kotlin 2.4.10; taking it breaks the Compose compiler unless KGP is *also* forced onto
  the root buildscript classpath. Move all three together, by hand, or none.
- **ktlint, detekt and JaCoCo are pinned.** A new rule in a linter makes files nobody touched stop
  passing, so CI goes red on a PR whose diff cannot explain it. Upgrading them is its own commit:
  run the tool, read the new findings, fix or relax them.
- **The FFmpeg AAR** is a committed file, not a coordinate.

**`+` does not mean "newest stable" on its own** — Gradle will happily resolve it to an alpha, and
androidx routinely publishes alphas numbered above the current stable (at last check: lifecycle,
navigation, work, datastore and annotation all did). The `componentSelection` block in
`app/build.gradle.kts` rejects prereleases, which is the only reason `2.+` means 2.11.0 rather than
2.12.0-alpha01. **Do not remove it.** To try a prerelease, name the exact version in the catalog —
that pins it, which is the right way round.

Because versions float, a build can change without a commit. `./gradlew :app:dependencies
--configuration debugRuntimeClasspath` shows what actually resolved.

## Traps

- **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin; applying the legacy
  plugin fails the build. This is why `libs.versions.toml` pins `kotlin` to AGP's bundled KGP
  version rather than the newest Kotlin release — the Compose compiler plugin must match it.
- **The FFmpeg AAR is committed** under `bin/`, deliberately. It is not on any Maven repo
  (ffmpeg-kit was archived and delisted). Rebuilding per CI run made red builds ambiguous: broken
  code, or a cross-compile that hiccuped? `bin/README.md` has provenance and how to regenerate it.
- **Anything touching Media3 carries `@UnstableApi`** rather than swallowing the marker with
  `@OptIn`. Android lint's `UnsafeOptInUsageError` catches a missed one.
- **Release builds ship both ABIs.** `-PabiFilters` is a test-run override only; `build.yml`
  verifies the released APK carries every ABI and that all native libraries are 16 KB aligned.
