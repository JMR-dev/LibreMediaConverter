# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

LibreMediaConverter is an Android media converter (Kotlin, Jetpack Compose, Material 3) with two
conversion engines: Media3 Transformer for the hardware path and FFmpeg for everything the platform
cannot do. See `@README.md` for the architecture and `@LICENSES/README.md` for the split license —
this file covers only what is not obvious from the code.

## Build, test, lint

Use a **JDK 17–21** for the Gradle daemon. AGP 9 will not run on anything older than 17, and does
not support 25+ — if `JAVA_HOME` points at 25, builds fail.

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
- **Coverage is reported, not gated** — currently ~31% of lines. A floor needs a baseline that has
  settled first.
- `kotlin.code.style=official`. Gradle stays Kotlin DSL.

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
