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

## Instrumented tests: where they actually run

This section said the opposite until 2026-08-24, and both of its claims had been false for two
days. Read it as the current answer, and see the git history if you need the old one.

- **Local emulators work, for API 33-36.** `tools/local-emulator/run-e2e.sh` runs them on this
  host. The segfault that made this look impossible was not a broken machine: SwiftShader's Reactor
  JIT writes generated shader code onto the heap and executes it, Fedora's SELinux policy denies
  `execheap`, and qemu dies. Choosing a different renderer avoids it entirely — `-gpu host`,
  `angle_indirect` and `swangle_indirect` all boot, while `auto`, `off`, `guest` and
  `swiftshader_indirect` do not. `docs/local-emulator.md` has the evidence and the per-API renderer
  table.
- **CI runs API 37, and it gates.** The matrix is 33/34/35/36/37. **Three** of the 59 instrumented
  tests cannot pass on that image, for two unrelated reasons: two Media3 hardware transcodes fail
  inside the emulator's own `c2.goldfish.h264.decoder`, and one SAF test takes the framework down
  when it rotates the display. All three carry `@FailsOnEmulatorApi37` and run in a separate
  `continue-on-error` job; the gating leg runs the other 56.

  That job is still called `E2E API 37 Media3 hardware transcode (advisory)`, which no longer
  describes everything in it. The name is kept deliberately — it is not a required context and
  people have learned to look for it — so **read the marker, not the name**, for what it holds.
  **It is red on every PR, by design**: do not read it as your change breaking something, and do
  not read a green run as evidence those three tests pass.
  `docs/api-37-emulator-crash.md` has the measurements.

  **That instruction is also why nobody looks, so the job now reports its own shape** — expected,
  received, failed, and whether the run completed — to the job summary, and compares it against
  `FAILS_ON_EMULATOR_API37_BASELINE`, committed beside the marker. A deviation is a `::notice::`;
  the job stays advisory and its conclusion is untouched. **Add or remove a `@FailsOnEmulatorApi37`
  and that number changes in the same diff**, or the next run says so. A bare failure count would
  not have worked: the run is usually truncated by an `INSTRUMENTATION_ABORTED`, and the test XML
  is written anyway and says nothing about it — `.github/scripts/e2e-report-shape.sh` is where that
  is measured and explained.

Still true, and the reason the advisory job is not simply deleted: **API 37 needs a manual check on
the Pixel 10 Pro XL before each release.** Those three tests are the one thing CI cannot answer
for.

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
- **Coverage is reported, not gated** — **69.2% of lines (1519/2194), 53.2% of branches**,
  measured 2026-08-24 with `./gradlew :app:jacocoTestReport`.

  **Every figure this file carried before that date was an artifact, roughly half the real one.**
  Robolectric loads classes through its own sandbox classloader with no source location, JaCoCo
  skips no-location classes by default, and nothing told it otherwise — so **not one Robolectric
  test counted**, and Robolectric is what exercises the framework edge here. The
  `isIncludeNoLocationClasses` block in `app/build.gradle.kts` is what fixes it; **do not delete
  it as stray config**, and re-run the numbers if you ever touch it. Same commit, same 335 tests:
  29.7% -> 69.2% with that block alone.

  The old entry also explained the wrong thing. It said coverage **fell** as the suite grew from 11
  test files to 43 because "the denominator outran the numerator" on framework-edge code "the JVM
  cannot reach". The JVM reaches that code fine. What actually happened is that the new tests were
  disproportionately Robolectric, so each one added denominator and no numerator — the measurement
  was punishing exactly the tests that were hardest to write.

  Two things still hold. A floor needs a baseline that has settled, and this one has now moved by
  39 points in a single build change, so it has not. And **re-measure before quoting** — that
  instruction is the only reason this was caught.
- **Testable code is not done until it is tested.** If a piece is unit testable, it gets unit
  tests before it counts as done. If it is e2e testable, it gets e2e tests. Both clauses apply —
  a change that is both needs both.

  Three things make that a real bar rather than a slogan here:

  - **Unit-testable is broader than it looks.** The pure-seam pattern — `work/FailureOutcome.kt`
    documents the reasoning — turns "needs a device" into "a pure function plus a thin edge".
    Robolectric is in the JVM source set, `compose-ui-test-junit4` with it, so Compose screens are
    unit testable too. Reach for the seam before concluding something cannot be unit tested.
  - **E2E is runnable locally**, API 33-36, via `tools/local-emulator/run-e2e.sh` — see
    "Instrumented tests: where they actually run" above. That was believed impossible until the
    SELinux/renderer cause was found, and it is what makes the e2e half of this norm enforceable.
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

  **`actionlint` covers the half shellcheck cannot see** — the inline `run:` blocks, where a good
  deal of this repo's bash lives. It runs shellcheck over each `run:` plus its own checks on
  expression syntax, `needs:` references, matrix keys and action inputs. It sits in the same job,
  **pinned by digest** for the reason above and one of its own: its documented installer is a
  `curl | bash` off a moving branch, which does not belong in a repo that pins every action by SHA.
  Locally: `podman run --rm -v "$PWD:/repo:z" -w /repo docker.io/rhysd/actionlint@sha256:9d360886... -color`.

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
