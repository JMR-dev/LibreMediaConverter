# Defect audit

**Status:** twelve fixed and merged, one parked, two open, one no-action. Four numbers, because
they have to sum to the sixteen entries below and the previous three did not. Fix status is per
entry in the summary table and
tracks `main`, re-checked at `18c53a3`; the entry bodies below describe each defect *as found* and
are deliberately not rewritten as fixes land — this is the record of what was wrong, not a
changelog.
**Scope:** the Android-framework edge of the app, which has no JVM unit tests.
**Last verified:** 2026-08-22, against `main` at `903b43c`.
**Device pass:** 2026-08-22 on a physical Pixel 10 Pro XL, API 37. Four entries were driven on
hardware; **D1 did not reproduce and its premise is contradicted** — see its entry. Verdicts are
marked per entry. Everything unmarked is still inspection only.

Instrumented baseline taken at the same time: `connectedDebugAndroidTest` on the Pixel gave
**49 tests, 0 failures, 0 errors, 2 skipped**, no regression against the 40/0/2 recorded in
`api-37-emulator-crash.md`. The 2 skips are the assumption-guarded `RealMediaBenchmark` tests.

> **Correction — 2026-08-23 (`R3 / #12`, `R12 / #21`, `R13 / #22`).** The status metadata in this
> document went stale within hours of being written, and this document is read as the work queue,
> so the corrections are stated rather than made quietly:
>
> - **D5 and D7 were marked `open`; both were already merged to `main`.** `b86df47` (D5 —
>   `InputQuery` plus `hasSpaceForUnknownSize`) and `c2e6344` (D7 — `setForegroundAsync`, no direct
>   `notify()` left outside a comment) are both ancestors of `18c53a3`, and
>   `fix/space-proxy-and-notification`, which the old text called "in progress", is merged.
>   A reader acting on the old table would have re-implemented merged work.
> - **The header count did not sum.** "Ten fixed and merged, two in progress, one parked" accounts
>   for thirteen of the sixteen entries below; D12, D15 and D16 fell out of it.
> - **"Where the fixes live" named a branch with no ref and a test total 15 short.** Corrected in
>   that section, with the reason it went stale.
> - **D11 was marked `merged` although one of its four rows was deliberately left undone.** Also
>   corrected in the summary table; `7db3200`'s own commit body says so and this did not.
>
> The entry bodies are untouched. All 34 of their as-found citations were re-checked against
> `903b43c` and are accurate; only status metadata and claims that had become false were changed.

This is a survey, not a work order. Each entry records what is wrong, how confident we are that
it is wrong, how to provoke it, and what a fix would have to decide. Acting on any of them is a
separate decision, and each would be its own commit.

## Why this document exists, and why it is not about detekt

The obvious place to look for defects is the static-analysis output. There is nothing there:

| Gate | Result on `903b43c` |
|---|---|
| `./gradlew :app:detekt` | **0 findings** across 41 files |
| `./gradlew :app:ktlintCheck` | clean |
| `./gradlew :app:lintDebug` | `0 errors, 0 warnings, 1 hint` |

There is also no `detekt-baseline.xml`, no `lint-baseline.xml`, and not one `@Suppress`,
`//noinspection` or `tools:ignore` anywhere in the repository. The entire suppression surface is
`config/detekt/detekt.yml` and the `lint {}` block in `app/build.gradle.kts`, each entry carrying
its reason in prose. **A detekt baseline would be an empty file**, so none is proposed.

Running detekt with `allRules` enabled produces 467 findings, which is misleading rather than
informative:

| Count | Rule | Verdict |
|---|---|---|
| 177 | `UndocumentedPublicProperty` | KDoc on every public property |
| 126 | `FunctionNameMaxLength` | backtick test names in `src/test` |
| 45 | `UndocumentedPublicFunction` | as above |
| 41 | `UndocumentedPublicClass` | as above |
| 33 | `DocumentationOver*`, `LabeledExpression`, `ClassOrdering`, `UseIfInsteadOfWhen`, … | style opinions |
| 2 | `OutdatedDocumentation` | **incorrect** — see D12 |

**Zero are in the `potential-bugs` ruleset.** Enabling `allRules` would mean writing KDoc for 177
public properties, which is the opposite of what `config/detekt/detekt.yml` says its own purpose
is: *"Genuine smells … are fixed in the code, not silenced."*

So the linters are clean and honest, and the defects are elsewhere — in the code they cannot see
into. `OutputPublisher`, both ViewModels, both Workers and `MainActivity` have **no JVM unit tests
at all**: roughly 1,200 of ~4,000 lines of main source, and the direct explanation for the ~31%
coverage figure recorded in `CLAUDE.md`. Every entry below is in that untested set.

## How to read the confidence labels

`api-37-emulator-crash.md` separates what was reproduced from what was ruled out. This does the
same, because an inventory that asserts a bug it cannot demonstrate is worse than a shorter one.

- **Confirmed by inspection** — the control flow is fully readable and the defect follows from it.
- **Needs device confirmation** — the reasoning is sound, but the behaviour depends on framework
  runtime semantics. Per `CLAUDE.md`, that means CI or the Pixel 10 Pro XL, never a local
  emulator. Each such entry states its *forcing condition* so the check is a task, not a hunch.
- **Latent** — not reachable through today's UI, but wrong, and one change away from being live.

Nothing below was observed on a device. The "confirmed" entries are confirmed as *code*; the
"needs device confirmation" entries are not confirmed at all yet.

---

## D1 — `hasSpaceFor` measures the wrong quantity

**Severity: medium · NOT REPRODUCED on the Pixel — the stated premise is contradicted**

*Originally filed as "under-reports free space". The device pass falsified that direction; the
title and reasoning are corrected here rather than quietly dropped.*

`app/src/main/java/org/libremediaconverter/convert/OutputPublisher.kt:35`

```kotlin
open fun hasSpaceFor(bytes: Long): Boolean = stagingDir.usableSpace > bytes + SPACE_HEADROOM_BYTES
```

`File.usableSpace` gets two things wrong: it ignores cache the system would reclaim on request,
and it counts the low-storage reserve — space the framework will not let the app have — as
available. The platform documents the first direction in `StorageManager` itself:
`getAllocatableBytes` *"is typically larger than `File.getUsableSpace()`, since the system may be
willing to delete cached files to satisfy an allocation request."*

**On the measured device the second effect dominates and the first is absent entirely**, which is
why this entry now reads "wrong quantity" rather than "under-reports". It is the one entry here
that a device disproved.

This is the one defect that was already known. It is recorded in three places — the `lint {}`
block at `app/build.gradle.kts:94-102`, the body of commit `65a94b4`, and the PR #5 description —
all saying the fix "deserves its own commit and its own test". It is held visible rather than
hidden by `informational += "UsableSpace"`, which is the single hint in the lint report.

**`OutputPublisher`'s own KDoc (lines 29-34) does not mention it.** That is the one place a
reader of the code would not learn about it.

### Reproduction: **NOT REPRODUCED — the measured relationship is inverted**

This entry predicted `getAllocatableBytes` would exceed `usableSpace`. On the Pixel 10 Pro XL at
66% free, it does not. Measured on the app's own staging volume:

```
usableSpace      = 655141146624 (624791 MB)   <- what hasSpaceFor() reads
allocatableBytes = 654616858624 (624291 MB)   <- StorageManager
allocatable - usable = -524288000 (-500 MiB)
```

A control experiment settles why: writing 3 GB into the app's own cache dropped **both** numbers
by the identical 3,255,443,456 bytes and left the delta at exactly `-524288000`. **None of the
app cache counted as reclaimable** — `cacheClearable` is zero here. `dumpsys diskstats` reported
`Data-Free: 639775620K / 959840256K total = 66% free` with `App Cache Size: 14623028224`, so
13.6 GB of device-wide app cache yielded zero reclaimable bytes. The −500 MiB is the low-storage
reserve, and nothing offsets it.

**What this does and does not settle.** It does not show the app is measuring the right thing —
counting the low-storage reserve as usable is still wrong, just wrong in the *other* direction.
What it kills is the stated justification: on this device, at this fill level, the app is not
refusing conversions it had room for, and switching to `getAllocatableBytes` would refuse
**more** jobs, by 500 MiB.

The under-report direction requires reclaimable cache to be non-zero, which needs real storage
pressure — plausibly the regime where the guard actually fires, but **unverified**. Reaching it
would mean filling the device far past 13.6 GB of cache, which was not done. Until someone
measures near-full, this entry's original premise stands unproven, and any fix should be
justified as "measure the right quantity" rather than "stop refusing jobs we had room for".

### What a fix has to decide

- **It is not a pure loosening.** `getAllocatableBytes` also excludes the framework's low-storage
  reserve, so on a nearly-full device it can return *less* than `usableSpace`. In that regime the
  fix makes the app refuse **more** jobs. That is correct — staging lives in `cacheDir`, the first
  thing the system reclaims, so writing into the reserve invites the staged output to be deleted
  mid-job — but it will read as a regression unless the commit message says so.
- **Report, do not allocate.** `allocateBytes` can clear the app's *own* cache to satisfy a
  reservation, so a pre-flight check for one job could destroy another job's unsaved staged
  output. A check that deletes results is worse than the bug it fixes.
- **The `IOException` policy is a real choice, not a detail.** `getAllocatableBytes` throws when
  the volume "isn't present, or doesn't support allocating space". Failing open (allow the job,
  let it fail later with a real `ENOSPC`) and failing closed (refuse) are both defensible; the
  decision belongs in a test, not in a `catch` block.
- **The code being replaced is executed by zero tests.** The only coverage,
  `FakeFailures.FullDisk` (`app/src/androidTest/java/org/libremediaconverter/fallback/FakeFailures.kt:70-72`),
  *overrides* `hasSpaceFor` rather than exercising it. Swapping a call that cannot throw for one
  that can, with nothing testing the real body, is the main risk here. An instrumented test that
  calls the real `hasSpaceFor` should land with the fix.

### Test to write first

A pure seam taking the measured space as a **nullable** `Long`, so "the platform could not measure
this volume" is a value a JUnit test can pass in:

1. room when available exceeds required + headroom
2. refused when exactly equal — pins the boundary
3. refused when below
4. the unmeasurable case resolves to the chosen policy — *this is the test that documents the
   decision*
5. an absurd reported input size cannot overflow into a wrong "yes"

### Registry action on fix

Delete `informational += "UsableSpace"` and its nine-line comment from `app/build.gradle.kts`.
`./gradlew :app:lintDebug` should then pass with no hint — locally verifiable, which is rare here.

---

## D2 — The app never cleans up its own staging files

**Severity: medium · Confirmed by inspection · REPRODUCED on the Pixel**

Every conversion writes a full-size output into `<cacheDir>/conversions/`. `save()` publishes it
to the user's destination and deletes it. But **`reset()` in both ViewModels drops the `File`
reference without deleting it**:

- `app/src/main/java/org/libremediaconverter/convert/ConversionViewModel.kt:237`
- `app/src/main/java/org/libremediaconverter/join/JoinViewModel.kt:122`

and `reset()` is what the **"Start over" button** on the `Converted` and `Joined` states calls
(`ConverterScreen.kt:195`, `JoinScreen.kt:143`). Converting a file and deciding not to save it is
an ordinary, first-class path through the UI, and it leaves a full-size copy behind every time.

`OutputPublisher.clearStaging()` (`OutputPublisher.kt:44`) exists to clean exactly this up and is
**called from nowhere**. `grep -rn --include='*.kt' 'clearStaging' app/src` returns one line: its
own declaration. Not even the instrumented tests reference it. It is dead code that documents the
author's own intent.

### Severity, stated honestly

This is **not** unbounded growth. `cacheDir` is OS-evictable under storage pressure, so the
platform reclaims it eventually. The defect is that the app relies on the OS to clean up after it:
until eviction the user sees inflated app storage for files that serve no purpose, and on a device
that is not under pressure they can sit there indefinitely.

### Reproduction

```
convert a file → "Start over"
adb shell run-as org.libremediaconverter ls -l cache/conversions/
```

The output file is still there. Repeat — one copy per conversion.

**Done on the Pixel**, driven through the real `ConversionViewModel` (pick → `convert()` →
`Converted` → `reset()`, which is what "Start over" calls). Staging was empty beforehand:

```
terminal state = Converted(..., staged=.../cache/conversions/input_converted.mp4, engineUsed=MEDIA3)
state after reset() = Idle
AFTER_RESET staged = .../input_converted.mp4 exists=true length=456190
```

and from the shell afterwards:
`-rw------- 1 u0_a540 u0_a540_cache 456190 input_converted.mp4`

### Other paths to the same leak

- **A failed save, and this one is worse than it looks.** `save()`'s `onFailure` sets
  `Failed(message)` — a state that carries no `staged` reference at all. So even a `reset()` that
  consulted the state could not find the file. Any fix needs the cleanup handle to be a ViewModel
  field, not something read back out of the state machine.
- **Process death mid-job** — see D3.

### `clearStaging()` cannot simply be wired up

It deletes everything in the directory unconditionally. That would include a live
`concat_list.txt` mid-join, or the other tab's in-flight output (D8). Whatever closes this defect
has to be narrower than the dead method is.

### Note the direction of the interaction with D1

These two do **not** compound, and it is worth being precise because the opposite is the intuitive
reading. `getAllocatableBytes` counts reclaimable cache, and the app's own `cacheDir` is
reclaimable — so once D1 is fixed, leaked staging files read as *available* space. **D1's fix
masks D2's effect on the space check rather than worsening it.** That is an argument for fixing
D2 first or alongside, not for treating them as one defect.

### Tests to write first

Two seams, because one cannot express both halves:

- a pure "which of these entries is collectable" rule over `(name, lastModifiedMs)` pairs and a
  clock — the interesting part is the boundary condition and a clock that moved backwards, and
  passing timestamps in directly keeps that exact rather than filesystem-dependent;
- a Robolectric test driving a real `OutputPublisher` against a temp `cacheDir`, asserting the
  file is actually **gone** after the reset path runs. No pure function can express that.

---

## D3 — A conversion that outlives the process becomes unreachable

**Severity: high · REPRODUCED on the Pixel**

This is the strongest claim in this document.

`ConversionViewModel.activeWorkId` and `observer` are plain fields
(`ConversionViewModel.kt:87-88`); nothing is persisted to a `SavedStateHandle`. WorkManager jobs
deliberately survive process death — that is the stated reason for choosing it
(`ConversionWorker.kt:33`: *"the queue survives process death"*).

So whenever the process is reclaimed, the next launch starts at `ConversionState.Idle` while the
output sits in cache, and **the user has no way to reach or save it**. It then stays there per D2.
The app is architected specifically to protect long jobs from process death, and the ViewModel is
where that protection stops.

Two windows, and the second is much wider than the first:

- **During the transcode** — the worker holds a foreground service, so the process is high
  priority and reclaim is unlikely, though not impossible under real memory pressure.
- **After it finishes, before the user saves.** The foreground service is gone, the process is an
  ordinary background one, and the result is sitting in cache waiting for a tap that may come
  hours later or never. This is the realistic case and the one to test.

### Reproduction (forcing condition)

The defect does not need the process to die *mid-job*. It needs the ViewModel to be gone while
the result exists — which is also the realistic case: the job finishes, the user never comes back,
and the process is reclaimed hours later.

```
convert a file and let it finish, so the screen reaches "Done"
do NOT tap Save; background the app
adb shell am kill org.libremediaconverter
```

Relaunch: the screen is Idle, and the finished output is still in `cache/conversions/`, with no
route to it from the UI.

**Done on the Pixel.** Process `23087` killed after backgrounding, relaunched as `23252`; the
456190-byte output survived. In a fresh process:

```
FRESH_PROCESS   input_converted.mp4  456190 bytes
WorkInfos by tag org.libremediaconverter.work.ConversionWorker: 2
  id=4b488279-... state=SUCCEEDED output=.../cache/conversions/input_converted.mp4
  id=767e021a-... state=SUCCEEDED output=.../cache/conversions/input_converted.mp4
fresh ConversionViewModel state = Idle
```

`ConverterScreen` renders `viewModel.state.collectAsStateWithLifecycle()` directly, so a ViewModel
at Idle is the screen at Idle.

**Two things this adds to the fix direction.** The tag query works as predicted — but it returned
**two SUCCEEDED infos carrying the same `output=` path** while only one file exists on disk. That
is D8's collision surfacing inside D3's own fix: "reattach to the unfinished work" is not
well-defined on real device state, and checking that the staged file exists does not
disambiguate. Finished work is also not pruned promptly, so any reattachment will routinely see
completed jobs from earlier sessions.

**Correction to the `am kill` caveat above:** the refusal observed was **adj-dependent**, and the
foreground-service claim was not isolated. `am kill` no-opped against the top-activity process
(pid unchanged) and succeeded once the app was backgrounded to `oom: cur=700, state=LAST`.
Producing a plain app process holding a conversion foreground service needed the UI, and the
device was secure-locked.

Two traps in getting this to fire:

- **`am force-stop` will not show it** — it cancels the work outright.
- **`am kill` only kills processes the system considers safe**, and it refuses one holding a
  foreground service. Both workers call `setForeground()`, so a kill attempted *while the
  conversion runs* silently does nothing. Wait until the job is finished (the foreground service
  is gone by then) or use `adb shell am crash org.libremediaconverter` instead.

### Fix direction

Reattach on init by querying WorkManager for the app's own unfinished work. **No production
change is needed to enable this** — WorkManager already tags every request with its worker class
name, so `getWorkInfosByTag(ConversionWorker::class.java.name)` works against the code as it
stands.

---

## D4 — `publish()` can leave a truncated file at the user's destination

**Severity: medium · Needs device confirmation**

`OutputPublisher.publish()` (`OutputPublisher.kt:38-42`) streams the staged file into the SAF
destination with `copyTo`:

```kotlin
open fun publish(staged: File, destination: Uri) {
    context.contentResolver.openOutputStream(destination)?.use { out ->
        staged.inputStream().use { it.copyTo(out) }
    } ?: error("Could not open destination for writing: $destination")
}
```

If `copyTo` throws partway — destination volume full, provider error — the partial file remains at
the user's chosen location, under the name they picked, while the UI reports "Could not save the
file". The user is left holding a broken file they were told was not written.

Whether the document survives depends on the provider, which is why this is not marked confirmed.

### Reproduction (forcing condition)

Fill the destination volume so it holds less than the staged output, then Save. Inspect the
destination: a same-named, short file is present. `UnopenableUriTest.kt:91-92` already covers the
*unopenable* destination case; this is the *fails-midway* case, which nothing covers.

### Fix direction

Delete the destination document on failure via `DocumentsContract.deleteDocument`, or
write-then-rename where the provider supports it.

---

## D5 — The space check can be effectively vacuous

**Severity: low-medium · CONFIRMED live on the Pixel**

`hasSpaceFor` is given the **input** size as a proxy for the output size, and that size comes from
`OpenableColumns.SIZE` in `queryFile()` (`ConversionViewModel.kt:265-281`,
`JoinViewModel.kt:129-143`):

```kotlin
var size = 0L                                    // ConversionViewModel.kt:267
…
cursor.getColumnIndex(OpenableColumns.SIZE)
    .takeIf { it >= 0 }
    ?.let { size = cursor.getLong(it) }
```

A provider that does not report `SIZE` leaves `size` at `0L`, and `hasSpaceFor(0)` degrades the
guard to "is there 128 MB free". The `0L` default is explicit and confirmed; **which real
providers omit the column is not confirmed**, and this document will not guess.

Separately, `hasSpaceFor`'s KDoc claims peak usage is "roughly input + output at once" while the
check only reserves `input + 128 MB`.

### Reproduction

**Observed on the Pixel as a reachable value, not an inference.** `contentResolver.query` on a
`file://` URI returns null, so `queryFile` never reaches the `SIZE` column and leaves the default
in place: `queryFile gave displayName='input' sizeBytes=0`. `hasSpaceFor(0)` then degrades the
guard to "is there 128 MB free", exactly as predicted.

Which *document-provider* URIs omit `SIZE` is still unconfirmed — the `file://` path is enough to
show the `0L` default is live, not enough to say how often a real pick hits it.

### Note

This shares a seam with D1. Both should be decided together, not in separate passes.

---

## D6 — Rotating the device throws the user back to the Convert tab

**Severity: medium · Confirmed by inspection**

`MainActivity.kt:65`:

```kotlin
var destination by remember { mutableStateOf(Destination.CONVERT) }
```

`remember` survives recomposition but not activity recreation, and `MainActivity` declares no
`configChanges`. Any rotation or resize resets the selected tab.

The KDoc immediately above that line argues the adaptive shell "is not cosmetic", because from
targetSdk 37 *"the app will be resized and rotated whether or not it is ready"* — which is exactly
the case that loses the state.

### Reproduction

Open the app, switch to the Join tab, rotate the device. It returns to the Convert tab. The
ViewModel state survives (both ViewModels are Activity-scoped); only the tab selection is lost,
which is what makes it visibly wrong rather than merely stale.

### Fix direction and test

`rememberSaveable`. A Compose `StateRestorationTester` test covers it, and
`compose-ui-test-junit4` is **already on the androidTest classpath with zero current users** — no
new dependency needed. It is an instrumented test, so it runs on CI, not locally.

---

## D7 — Direct `notify()` on WorkManager's foreground notification ID

**Severity: low-medium · Resurrection REPRODUCED; undismissability NOT verified**

`ConversionWorker.publishProgress()` (`ConversionWorker.kt:167-169`) calls the notification manager
directly, on the same ID WorkManager owns through `setForeground`:

```kotlin
applicationContext.getSystemService(android.app.NotificationManager::class.java)
    .notify(NOTIFICATION_ID, notifications.build(id, displayName, percent))
```

A progress update landing after the worker is stopped can resurrect a notification built with
`setOngoing(true)` (`ConversionNotifications.kt:41`).

**REPRODUCED on the Pixel**, on attempt 3 of 12, cancelling a `BEST`-tier job:

```
attempt 3: terminal state = CANCELLED
attempt 3: +300ms  active=0 id1001=false ongoing=null      <- WorkManager tore it down
attempt 3: +700ms  active=1 id1001=true  ongoing=true      <- resurrected
attempt 3: +5000ms active=1 id1001=true  ongoing=true
```

Still live ~10 minutes later with no app process at all. The record shows
`flags=ONGOING_EVENT|ONLY_ALERT_ONCE` and **no `FOREGROUND_SERVICE` flag** — which is what proves
`publishProgress()`'s direct `notify(1001, …)` posted it rather than `setForeground`.

**An earlier draft of this entry claimed the user "cannot dismiss" it. That is unproven and may
be wrong.** `isClearable()` returned false, but only because `FLAG_ONGOING_EVENT` is set: the
record carries no `FOREGROUND_SERVICE` and no `NO_CLEAR`, and API 34+ lets users swipe away
ongoing notifications that are not foreground-service-backed. A SystemUI swipe test was
impossible behind the secure lock screen, so the severity of the orphan is still open.

### Reproduction (forcing condition)

Cancel a conversion at the moment a progress tick fires — the throttle is ~1/sec
(`NOTIFICATION_INTERVAL_MS`), so repeat cancels mid-conversion. Then check for an ongoing
notification with no running job: `adb shell dumpsys notification`.

---

## D8 — Nothing gives a job a staging path of its own

**Severity: medium, in a narrow window · Naming OBSERVED live; concurrency still unconfirmed**

Three paths into `<cacheDir>/conversions/` can be shared by two jobs at once:

| Site | Name | Collides when |
|---|---|---|
| `ConcatWorker.kt:57` | `"joined.${format.extension}"` — a **constant** | any two joins of the same format |
| `ConversionWorker.kt:90` | derived from the input display name via `outputNameFor` | two inputs share a display name (two `holiday.mp4` from different folders) |
| `ConcatEngine.kt:46` | `"concat_list.txt"` — a **constant**, `finally`-deleted | any two joins at all |

**As plain overwrite this is not data loss.** By the time a second job can start, the first result
is already unreachable: the only route from `Converted`/`Joined` back to a startable state is
"Start over", which clears it.

**The live consequence is corruption.** Neither ViewModel uses a unique-work policy — both call
plain `workManager.enqueue(request)` (`ConversionViewModel.kt:159`, `JoinViewModel.kt:65`) — so
after a process restart WorkManager can resume an earlier job while the user starts a new one, and
two FFmpeg processes write the same path concurrently. For `concat_list.txt` that means one join
reading the other join's input list.

### Reproduction

**The naming half needs no device**, and was then seen live anyway. Two independent jobs on the
Pixel — the D2 run and the D3 run — each computed
`cache/conversions/input_converted.mp4`, and the second silently overwrote the first. The D3 probe
also found **two SUCCEEDED `WorkInfo`s carrying that same path** with only one file on disk, which
is the collision reaching the point where it makes a *fix* ambiguous, not just a file.

**The concurrency half is harder to provoke than it looks**, and is the reason this entry is not
marked confirmed. It requires a join that is still non-terminal when the process dies, so that
WorkManager resumes it alongside a newly started one — and the same `am kill` caveat as D3
applies, since a running worker holds a foreground service. A job that already returned
`Result.success` will not resume at all. Anyone checking this should say which of the two they
relied on; if it cannot be provoked, the entry should be reduced to the naming half alone, which
stands on inspection.

### Note

This is also why D2's `clearStaging()` is hazardous: a directory-wide delete would take out a live
list file mid-join.

---

## D9 — Output names are derived from the wrong source

**Severity: low · Latent, two instances**

`JoinViewModel.kt:115` reports a hardcoded name on success:

```kotlin
_state.value = JoinState.Saved("joined.mp4")
```

regardless of format. `ConcatWorker.request` already takes a `format` parameter defaulting to
`MP4_H264`, and `JoinViewModel.join()` never passes one, so the string is *accidentally* correct.
`JoinScreen.kt:44` (`CreateDocument("video/mp4")`) and `:139` (`launch("joined.mp4")`) hardcode the
same assumption. All three agree only because the Join screen has no format picker.

The same shape is in the convert path: `ConversionViewModel.save()` (`:226`) and
`suggestedOutputName()` (`:244`) build the name from **`_settings.value.spec` — the current picker
state, not the spec the job actually ran with**. Today the pickers render only in the `Ready`
state, so settings cannot change between enqueue and save, and the name is right by accident too.

Both become wrong the moment a format picker reaches the Join screen, or the pickers stay live
during a conversion. Recorded as one pattern rather than two footnotes, because it is one mistake
made twice.

### Test to write first

Pure name-derivation seams keyed on the job's own spec/format, mirroring the existing
`ConversionWorker.outputNameFor` and its assertions.

---

## D10 — `CancellationException` is caught and converted to a `Result`

**Severity: low · Needs device confirmation before being called a defect at all**

`ConversionWorker.doWork()`'s outer `catch (e: Throwable)` (`ConversionWorker.kt:104`) catches
`CancellationException` — `runMedia3OrFallBack` deliberately rethrows it via `isCancellation` —
and routes it into `handleTimeoutIfNeeded`, returning `Result.failure`/`Result.retry` rather than
letting it propagate. Swallowing cancellation inside a coroutine breaks structured concurrency.

**The user-visible impact today is probably nil.** WorkManager marks work `CANCELLED` itself and
ignores the returned `Result`. The `staged.delete()` on that path is desirable and any fix must
preserve it.

### Reproduction (forcing condition)

Cancel a running conversion and read the resulting `WorkInfo`. If its state is `CANCELLED` and no
error surfaces to the UI, this is confirmed harmless and should be **downgraded to a note** rather
than carried as a defect.

---


## D11 — Documentation and scaffold defects

**Severity: low · Confirmed by inspection**

*Three of the four rows below are fixed on `main` by `7db3200`; the second row is not, and the
summary table said "merged" without saying so (`R13 / #22`). `7db3200`'s own commit body records
the decision — "Not touched: `OutputPublisher`'s `hasSpaceFor` KDoc … that code belongs to a
parked branch and another change stream" — so the row is **held with D1 on
`fix/allocatable-space`**, not forgotten. It is still true today: nothing in `OutputPublisher.kt`
mentions D1, which leaves a reader of that code with no way to learn the parked defect exists.
The row itself is left as written, like every other as-found body in this file.*

| Item | Location | Note |
|---|---|---|
| Stale JDK claim | `README.md:111` | "Requires JDK 17+ (AGP 9 will not run on older) and the Android SDK with API 37." contradicts the Java 25 toolchain that `CLAUDE.md` documents. The same claim was already corrected once, in `CLAUDE.md`, by commit `f496291`. |
| Known bug not recorded at the code | `OutputPublisher.kt:29-34` | `hasSpaceFor`'s KDoc does not mention D1, though three other places record it. |
| Stale package directory | `app/src/main/java/com/example/androidmediaconverter/` | Empty; residue from the project template's old package name. |
| Template TODO | `app/src/main/res/xml/data_extraction_rules.xml:8` | Untouched Android Studio boilerplate, and the only literal `TODO` in the repository. |

---

## D12 — Two detekt `allRules` findings warrant no code change

**Recorded so that nobody "fixes" correct code**

With `allRules` enabled, detekt 2.0.0-alpha.6 reports `OutdatedDocumentation` twice:

| Finding | Claim | Reality |
|---|---|---|
| `ContainerCapabilities.kt:338` | documented parameter `suggestions` "is not present in the declaration" | `data class Invalid(val message: String, val suggestions: List<OutputSpec>)` — it is present |
| `OutputFormat.kt:18` | documented parameter `ffmpegFormat` "is not present in the declaration" | `enum class Container(val label: String, val ffmpegFormat: String, …)` — it is present |

The KDoc is correct in both cases, so no code change is warranted. Why detekt emits them is not
investigated here and this document will not speculate.

This matters beyond the two lines: it is the concrete evidence for leaving `allRules` off. A rule
that reports correct code as wrong would cost more in re-litigation than the 465 style findings
next to it.

---

## D13 — Work interrupted by process death fails terminally instead of resuming

**Severity: high · CONFIRMED ON NATURAL DISPATCH, Pixel 10 Pro XL, API 37**

*This is the most serious entry in this document. It falsifies the premise the app's whole
background architecture rests on.*

Found during the Pixel pass, not present in the original inventory.

`ConversionWorker.setForeground(...)` is at line 66; the `return try {` is at line 92. **Any throw
from `setForeground` escapes `doWork()`** without reaching `handleTimeoutIfNeeded`,
`Result.retry()`, or `staged.delete()` — so no `KEY_ERROR` reaches the UI, no retry is attempted,
and a partial staged file is left behind. `ConcatWorker` has the same shape (`setForeground` at 49,
`try` at 58). That much is plain from the source.

What makes it potentially serious is what the device did with it. When WorkManager tried to
restart a `ConversionWorker` with the app in the background:

```
WM-WorkerWrapper: Starting work for org.libremediaconverter.work.ConversionWorker
ActivityManager: Background started FGS: Disallowed [callingPackage: org.libremediaconverter;
  targetSdkVersion:37; callerTargetSdkVersion:37]
WM-WorkerWrapper: android.app.ForegroundServiceStartNotAllowedException: startForegroundService()
  not allowed: service org.libremediaconverter/androidx.work.impl.foreground.SystemForegroundService
WM-WorkerWrapper: Worker result FAILURE
```

**FAILURE, not retry.** If that holds, it contradicts `ConversionWorker.kt:33` — *"the queue
survives process death"* — which is the app's stated reason for choosing WorkManager at all, and
the premise D3's fix rests on.

**The forcing caveat is retired.** A follow-up run reproduced this on a genuinely natural
dispatch, with no `cmd jobscheduler run` issued at any point (verified against the device's own
adbd command census). An 18-minute transcode was killed mid-write with `kill -9`; **119 seconds
later, unprompted**, WorkManager recovered it and the system denied it:

```
18:52:42.023 WM-ForceStopRunnable: Found unfinished work, scheduling it.
18:52:42.409 ActivityManager: Background started FGS: Disallowed [callingPackage:
   org.libremediaconverter; uidState: CEM; BFGS denied: true; code:DENIED;
   tempAllowListReason:<null>; targetSdkVersion:37; callerTargetSdkVersion:37]
18:52:42.425 WM-WorkerWrapper: android.app.ForegroundServiceStartNotAllowedException
18:52:42.429 WM-WorkerWrapper: Worker result FAILURE  [tags={ ...ConversionWorker }]
18:52:42.467 WM-Processor: Processor 3d1c9862 executed; reschedule = false
```

`Found unfinished work, scheduling it` is the clean process-death recovery path — **not**
"Application was force-stopped". The system logged its own `am_wtf` for the denial. A second
dispatch, via the force-stop recovery path and with the phone in active human use, was denied
identically — so the denial is not specific to how the work was re-enqueued, nor to the device
being idle.

**The previous "roughly two minutes" wait stopped essentially at the moment it would have fired.**
Forcing was never necessary, and the forced result was correct.

### What this costs the user

- **`reschedule = false`.** Terminal. Nothing runs again, despite `run_attempt_count=2`.
- **No error message reaches the UI at all.** Both failed rows carry output `Data` of
  `X'ABEF000100000000'` — the header with **zero entries**. The throw escapes at `setForeground`
  (line 66), above `return try {` (line 92), so `handleTimeoutIfNeeded`, `Result.retry()`, the
  `workDataOf(KEY_ERROR …)` and `staged.delete()` are all bypassed. `ConversionState.Failed` then
  renders its generic fallback.
- **A partial file is orphaned.** 2 MB of `long_input2_converted.mp4` was left in staging because
  `staged.delete()` is unreachable — feeding D2.
- **`HAS_FOREGROUND_EXEMPTION` was set on the job and it was still denied.** That flag governs
  runtime guarantees once started, not permission to start.
- **The job is ordinary** — `Priority: 300 [DEFAULT]`, not expedited, not user-initiated. There is
  no allowance a natural dispatch could carry that forcing withheld.

**One observation with implications beyond this entry:** the *initial* foreground-service start
succeeded during testing only because instrumentation was active (`code:ACTIVITY_STARTER`,
`allowWiu:52`) — an allowance the app does not have in production either. What grants it in normal
use is the user launching the app; nothing grants it on a background restart.

### Relationship to the other entries

D3's fix makes this *visible* rather than silent — a reattached FAILED job with blank output data
falls back to "Conversion failed." instead of an empty screen — and D2's sweep collects the
orphaned partial. **Neither addresses the cause.** The user still loses a long conversion that the
architecture promised would survive.

---

## D14 — A failed FFprobe load crashes the pick instead of reporting it

**Severity: low (rare trigger) · Confirmed empirically on the JVM · Cannot fire on a device that ships the libraries**

Found while building D2's ViewModel tests, not present in the original inventory.

`MediaProbe.probeWithFFprobe` guards its FFprobe call with `catch (e: Exception)`. But when
FFmpegKit's native library cannot be loaded, the failure arrives as a bare **`java.lang.Error`**,
not an `Exception`:

```
java.lang.Error: FFmpegKit failed to start on brand: robolectric ...
```

`Error` is not a subclass of `Exception`, so that catch does not see it. `ConversionViewModel.onInputPicked`
does not catch it either, and it runs inside `viewModelScope.launch` — so the failure propagates as
an uncaught error rather than the "could not read this file" outcome the surrounding code is
written to produce.

**Why the trigger is rare, and why it was still worth recording.** On a normally-installed app the
`.so` files are present and this cannot happen; it was observed on the JVM, where they are absent by
construction. A corrupted install or an ABI mismatch is the only realistic device path. That is also
why it was *not* fixed on discovery: widening the catch to `Throwable` changes the file-pick path on
a condition no ordinary user meets, and swallowing `Error` indiscriminately would hide genuine
`OutOfMemoryError`s in a method that spawns a native process.

**Note the shape.** This is the same class of mistake as D1's original `catch (IOException)` being
too narrow for a platform call that throws `RuntimeException`. Both are "the guard does not cover
what the boundary actually throws". Worth checking the other native boundaries — `FFmpegEngine`,
`ConcatEngine`, `Media3Engine` — for the same gap before calling this one closed.

---

## D15 — An oversized suggested name turns a finished conversion into a failure

**Severity: low (very narrow trigger) · Found while fixing D9 · Not fixed**

`Data.Builder.build()` throws above 10 KB, and both workers build their **success** `workDataOf(...)`
*inside* the `try`. So a `KEY_SUGGESTED_NAME` large enough to push the output `Data` over the cap
would be caught by the surrounding handler: the conversion is reported **failed**, and
`staged.delete()` removes the finished file.

The trigger is genuinely narrow. Input `Data` already carries `KEY_DISPLAY_NAME` and is built at
`enqueue()` time, so it would have to survive that; the failure then needs a display name landing in
roughly a **74-byte window near 10 KB**. A provider would have to supply a filename of about that
length exactly.

Left alone deliberately when D9 landed — capping the name is its own change with its own decision
(truncate where? preserve the extension?), and doing it inside a naming commit would have buried it.

**Fix direction:** cap the suggested name before it reaches `Data`, or build the success `Data`
outside the `try`. The second is smaller but changes which failures delete the staged file, so it
needs its own test.

---

## D16 — A job that exhausts its foreground-start retries reports to nobody

**Severity: low-medium · Found while fixing D13 · Not fixed**

D13's fix bounds foreground-start retries at 10 attempts and then reports `FOREGROUND_DENIED` —
a real message, replacing the empty output `Data` the device measured. But that message arrives on a
**FAILED** job, and `Reattachment.choose` (D3's fix) deliberately **excludes FAILED** work.

So a user who is not watching at the eleventh attempt — roughly 8.5 hours after the job was
enqueued, given the measured backoff — opens the app to an empty screen. What the bound reliably
buys is the *end* of the retrying, not the telling; `MAX_FOREGROUND_START_ATTEMPTS`'s KDoc now says
exactly that rather than implying more.

**Fix direction:** let reattachment surface a terminal failure that carries a message, distinct from
one that does not. That means changing `Reattachment.choose`'s exclusion rule, which was explicitly
out of scope for the commit that created the situation.

---

## Summary

| ID | Defect | Severity | Evidence | Fix |
|---|---|---|---|---|
| D13 | Interrupted work fails terminally instead of resuming | high | **confirmed on natural dispatch** | **merged** |
| D3 | A conversion outliving the process becomes unreachable | high | **reproduced on the Pixel** | **merged** |
| D2 | Staging files are never cleaned up | medium | **reproduced on the Pixel** | **merged** |
| D8 | No job gets a staging path of its own | medium | naming **observed live**; concurrency unconfirmed | **merged** |
| D4 | `publish()` can leave a truncated file at the destination | medium | not attempted | **merged** |
| D6 | Rotation resets the selected tab | medium | inspection only | **merged** |
| D1 | `hasSpaceFor` measures the wrong quantity | medium | **NOT reproduced — premise contradicted** | parked, see below |
| D5 | The space check can be vacuous | low-medium | **confirmed live** | **merged** |
| D7 | Direct `notify()` on WorkManager's notification ID | low-medium | resurrection **reproduced**; undismissability unverified | **merged** |
| D9 | Output names derived from the wrong source | low | latent | **merged** |
| D10 | `CancellationException` swallowed | low | not attempted | **merged** |
| D11 | Documentation and scaffold | low | inspection only | **merged**, less the `OutputPublisher` KDoc row — held with D1 |
| D12 | Two detekt findings that are wrong | n/a | inspection only | no action — correct as written |
| D14 | A failed FFprobe load crashes the pick | low (rare trigger) | **confirmed on the JVM** | **merged** |
| D16 | Exhausted foreground-start retries report to nobody | low-medium | found while fixing D13 | open |
| D15 | An oversized suggested name fails a finished conversion | low (very narrow) | found while fixing D9 | open |

**Where the fixes live.** All twelve merged fixes — D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D13
and D14 — are on `main`; there is no integration branch left to check out. *This paragraph used to
send the reader to `feat/defect-fixes-base`, which has no ref at all — not local, not remote, only
a reflog entry — because it was deleted when it merged, and to `fix/space-proxy-and-notification`
as still "in progress", which is merged too (its branch pointer does survive, at `c2e6344`).* The
JVM suite gates green on `main` at `18c53a3` — **257 tests, 0 failures, 0 errors, 0 skipped,
detekt 0, lint clean**, against the 242 this paragraph used to quote and the 180 before the fixes
began. That total is only as fresh as this commit; the 15 it was short are `UnknownInputSizeTest`,
`SpaceCheckTest` and `ProgressNotificationTest`. Re-derive rather than trust it:
`./gradlew :app:testDebugUnitTest`, then read `app/build/test-results/`. **D1 is parked unmerged** on
`fix/allocatable-space`: the code is sound but the device pass contradicted its stated premise, so
landing it needs a near-full-disk measurement first — see its entry. D15 and D16 were both found *while fixing* other entries and are
recorded rather than folded in silently.

Separately, `tools/local-emulator` carries the finding that **local emulators do work** — the
segfault was SwiftShader's JIT against Fedora's SELinux `execheap` denial, and API 33–36 now run
locally, every level matching the Pixel. That branch is merged. The sweep counted **49 tests per
level at `22c7914`**, the commit that ran it — anchored here rather than left bare, because the
instrumented suite has grown since (57 `@Test` on `main`) and an unanchored total invites a reader
to mistake drift for breakage. The durable invariant is the shape, not the total: 0 failures,
0 errors, 2 skipped, and the same total at every level and on the Pixel.
`docs/local-emulator.md` has the backtrace and the mode matrix, and proposes a `CLAUDE.md`
correction that has not been applied.

### If these are fixed

The order is not arbitrary:

1. **D3** — highest severity, and independent of everything else.
2. **D8 before D2** — a sweep's notion of "orphan" means nothing until a staging name belongs to
   exactly one job. **D9 rides along**, since it touches the same name derivations, and leaving
   one corrected literal beside an uncorrected one is worse than fixing both.
3. **D2, then D1 last** — because D1's fix would otherwise mask D2 in the space check.
4. **D11** — independent cleanup, any time.

Per `CLAUDE.md` and this repository's history, each concern is its own commit on its own branch
through a PR, never on `main`.

### On testing these

The JVM test source set has exactly one dependency, `testImplementation(libs.junit)`. That is why
every well-tested class in this project is pure (`model/`, `FFmpegCommandBuilder`,
`FailureOutcome`) and every untested one takes a `Context`. Instrumented tests cannot run on the
development host (`CLAUDE.md`, "Instrumented tests do not run locally"), so an androidTest-only
red test is not a TDD loop anyone can execute here.

The approach chosen for the follow-up work is **pure seams plus Robolectric**: extract each
decision into a pure function on the existing JUnit 4 stack — the pattern `work/FailureOutcome.kt`
documents in its own KDoc — and add Robolectric for the file-lifecycle behaviour a pure function
cannot express. Adding it needs a **pinned** `testImplementation` entry (the `componentSelection`
prerelease guard in `app/build.gradle.kts` covers only `androidx.`, `junit` and `com.arthenica`,
so a new group would float unguarded) and a `testOptions { unitTests.isIncludeAndroidResources = true }`
block, which this module does not currently have at all.

Worth knowing before adding anything: **`androidx.work:work-testing`, `compose-ui-test-junit4` and
`espresso-core` are already declared and have zero users.** `TestListenableWorkerBuilder` and
`createComposeRule` are available on the androidTest classpath today with no build change.

## Not covered here

- **The API 37 emulator crash** — fully documented in [`api-37-emulator-crash.md`](api-37-emulator-crash.md).
  Its one open action is unchanged: the upstream issue is still *"Not yet filed."*
- **The 467 `allRules` findings** — see the opening section. 402 are documentation and
  test-name-length opinions, 2 are wrong (D12), none are potential-bugs.
- **`CognitiveComplexMethod`** on `FileCard`, `ConversionViewModel.observe` and
  `JoinViewModel.join`. The rule is off by default, and this project already forgives Composable
  complexity deliberately.
- **The coverage gate.** Still reported, not gated, at ~31%. Fixing the entries above would move
  the number, which is another reason not to set a floor before they are decided.
