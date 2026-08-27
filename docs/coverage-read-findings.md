# Coverage-read findings

**Status:** five findings, none fixed, none urgent. F5 was added on 2026-08-27, found while decomposing #132 into children — it had been listed there as a test gap, and is not one. Every entry here is a *code* observation —
something a test would document rather than repair. The test gaps found in the same read are
tickets #132 and #133, not entries here; see [Not covered here](#not-covered-here).
**Scope:** what a JaCoCo read on 2026-08-26 turned up that writing a test would not fix. This is
a survey, not a work order. Acting on any entry is a separate decision and would be its own commit.
**Last verified:** `main` at `dc8b7c3`, 2026-08-26. Coverage re-measured that day with
`./gradlew :app:jacocoTestReport`: **84.9% line (1971/2321), 63.8% branch (900/1410)**, against
**456 JVM tests in 68 classes**. `CLAUDE.md` quotes 454 in 67 from four hours earlier; the
percentages are unchanged, so no figure there is stale.

## Why this document is separate from `defect-audit.md`

`defect-audit.md` is the record of the 2026-08-22 defect sweep: sixteen entries, each a thing that
is *wrong at runtime*. Nothing here is wrong at runtime today. These are arms that cannot be
reached, accessors nobody calls, and one KDoc that contradicts the code beside it — the category
`defect-audit.md` calls **latent**, plus one that is not a defect at all and is recorded so the
next coverage read does not re-file it.

They are here rather than in that document because folding them in would inflate a sixteen-entry
audit whose status metadata has already gone stale once, and because they share a provenance:
every one fell out of reading a coverage report, and every one is the kind of thing a coverage
report is *good* at surfacing and a test is bad at fixing. F5 is the clearest case — it was filed
as a test gap first, and only stopped being one when someone went looking for its callers.

Entry ids are `F1`–`F5` so they cannot be confused with `defect-audit.md`'s `D1`–`D16`.

## How to read the confidence labels

Same vocabulary as `defect-audit.md`, deliberately, so the two read alike:

- **Confirmed by inspection** — the control flow is fully readable and the finding follows from it.
- **Latent** — not reachable through today's UI, but wrong, and one change away from being live.
- **No action** — recorded because it looks like a finding and is not.

Nothing below was observed on a device, and nothing below needs to be: every entry is a claim about
what the code says, checkable by reading it.

---

## F1 — `FFmpegCommandBuilder` emits a Vorbis encoder that `ContainerCapabilities` says does not exist

**Severity: low · Latent · the more interesting reading is a missing feature, not dead code**

```
app/src/main/java/org/libremediaconverter/ffmpeg/FFmpegCommandBuilder.kt:188
app/src/main/java/org/libremediaconverter/model/ContainerCapabilities.kt:84-91
```

`FFmpegCommandBuilder.audioArgs` carries a live Vorbis arm:

```kotlin
AudioCodec.VORBIS -> listOf("-c:a", "libvorbis", "-q:a", "5")
```

`ContainerCapabilities` states, immediately above the set that governs it, that no such thing
exists:

> `/** Vorbis is absent for the same reason: nothing here emits a Vorbis encoder. */`
> `private val ENCODABLE_AUDIO = setOf(AAC, OPUS, MP3, FLAC, PCM)`

One of those two is wrong. The comment is the one that is wrong as written — something here does
emit a Vorbis encoder, twelve lines of `FFmpegCommandBuilder`.

### Why the arm is unreachable today

Traced, not assumed:

| step | where | effect |
|---|---|---|
| `validate` runs before routing | `ConversionWorker.kt:123` | a spec is checked on every job, however it was enqueued |
| `validateAudio` refuses non-encodable | `ContainerCapabilities.kt:246-251` | `VORBIS !in ENCODABLE_AUDIO` → `Invalid("This app cannot encode Vorbis audio.")` |
| the only spec→plan encode path | `CopyPlanner.kt:104` | `AudioPlan.Encode(requested)` — but `requested` cannot be Vorbis by the row above |
| the fallback encode path | `CopyPlanner.kt:112-115` | draws from `encodableAudio(container)`, itself filtered by `ENCODABLE_AUDIO` |

So `AudioPlan.Encode(VORBIS)` is not constructible through the app, and line 188 is dead.

### The reading that matters more

`CARRIES_AUDIO` lists Vorbis for WebM (`ContainerCapabilities.kt:62`) and OGG (`:67`). Because
`encodableAudio` filters through `ENCODABLE_AUDIO`, the picker offers **Opus and nothing else** for
WebM, and Opus/FLAC for OGG. FFmpeg on this device can encode Vorbis — the command is written and
correct — and the app declines to offer it.

So the honest framing is not "delete a dead arm". It is: **is `ENCODABLE_AUDIO`'s omission of
Vorbis a deliberate product call, or an accident that has been costing WebM/OGG users a format the
app already supports?** Nothing in the repo records that decision.

### The precedent for whichever way it goes

`Media3Engine.audioMimeTypeFor` has the *same* Vorbis arm, and handles it exactly right
(`Media3Engine.kt:221-233`): the KDoc names it dead, says why the arm stays anyway ("deleting a
right answer out of unreachable code buys nothing"), and points at `Media3EngineMimeTypesTest`,
which asserts which three of six codecs actually arrive — so the set moving fails a test rather
than surprising someone.

`FFmpegCommandBuilder`'s arm has none of that. Whatever is decided, the fix is to make the two
files agree and to say so in one place.

### What a fix has to decide

1. Whether Vorbis belongs in `ENCODABLE_AUDIO`. If yes, this is a feature and needs an e2e test
   that produces a playable Vorbis file; if no, go to 2.
2. Correct the `ContainerCapabilities.kt:84` comment, which is false as written, and give the
   `FFmpegCommandBuilder` arm the treatment `Media3Engine.kt:221-233` already models.

---

## F2 — `ConversionRequest.hardwareEncodeAvailable` is written, read by nothing, and its KDoc describes behaviour that was removed

**Severity: low · Confirmed by inspection**

```
app/src/main/java/org/libremediaconverter/model/OutputFormat.kt:211-219
app/src/main/java/org/libremediaconverter/work/ConversionWorker.kt:117
```

The property is set on every request:

```kotlin
hardwareEncodeAvailable = devices.canEncode(spec.videoCodec),
```

`grep -rn 'hardwareEncodeAvailable' app/src/main` returns **that line and nothing else**. No
production code reads it. Its getter is one of three uncovered methods in `OutputFormat.kt`, which
is what surfaced it.

Its KDoc (`OutputFormat.kt:211-218`) explains at length what it is for:

> Knowing this lets the Fast tier choose a genuinely fast software preset instead of a mislabelled
> slow one.

`FFmpegCommandBuilder` no longer does that, and its own test says so —
`FFmpegCommandBuilderTest.kt:132`, `the encoder choice no longer depends on hardware availability`:

> Once FFmpeg stopped selecting MediaCodec encoders, this flag only affects whether the router sends
> the job to Media3 at all — not what FFmpeg does.

That second clause is also not true. `ConversionRouter` decides hardware encodability by calling
`device.canEncode(videoEncode)` itself (`ConversionRouter.kt:153`); it never reads
`request.hardwareEncodeAvailable`. The flag is computed from the same source the router
independently consults, carried through the request, and dropped.

This is the shape of open issue **#68** — a KDoc promising a switch that does not exist.

**Not harmful.** It costs one `canEncode` call per job and a field on a data class. It is recorded
because the KDoc actively misleads: a reader changing the Fast-tier preset logic would look here
first, and this is not where that decision lives.

### What a fix has to decide

Whether to delete the property (and the constructor parameter, and the four
`FFmpegCommandBuilderTest` call sites that pass it) or to keep it and rewrite the KDoc to say it is
vestigial. Deleting is cleaner; the test at `:132` is worth keeping either way, since it pins the
"FFmpeg does not select MediaCodec encoders" rule that the deletion would otherwise erase.

---

## F3 — `ConversionRequest.videoCodec` and `.audioCodec` have no callers anywhere

**Severity: low · Confirmed by inspection**

```
app/src/main/java/org/libremediaconverter/model/OutputFormat.kt:222-223
```

```kotlin
val container: Container get() = spec.container      // used: FFmpegConcatCommand.kt:42, :80
val videoCodec: VideoCodec get() = spec.videoCodec   // no callers
val audioCodec: AudioCodec get() = spec.audioCodec   // no callers
```

Three delegating accessors on `ConversionRequest`; the first is used twice, the other two are used
nowhere in `main`, `test` or `androidTest`. Everything that wants those values reads
`request.spec.videoCodec` or takes the `OutputSpec` directly.

**This is not a test gap and must not be filed as one.** A test asserting
`request.videoCodec == request.spec.videoCodec` is vacuous by construction — it restates the
implementation and would pass against any delegation, right or wrong. That is precisely the failure
mode `CLAUDE.md` records from the mutation review (9 of 46 mutations vacuous, five over completely
unguarded paths).

The two accessors are either convenience worth keeping for symmetry with `container`, or two lines
to delete. Deleting them costs nothing and removes two uncovered methods that will otherwise be
re-found by every future coverage read.

---

## F4 — Two guards are reachable only by direct call, and that is correct

**Severity: n/a · No action**

```
app/src/main/java/org/libremediaconverter/ffmpeg/FFmpegCommandBuilder.kt:167-168
app/src/main/java/org/libremediaconverter/model/ConversionRouter.kt:175-176
```

```kotlin
VideoCodec.COPY, VideoCodec.NONE -> error("encodeVideo called for $codec, which is not an encode")
```

```kotlin
if (plan.video == VideoPlan.Copy && video == null) return false
if (plan.audio == AudioPlan.Copy && audio == null) return false
```

Both sit in private functions (`encodeVideo`, `media3CanMux`), and both are unreachable because a
caller upstream already excluded the case — which each says in its own comment. `ConversionRouter`'s
is labelled "the second line of defence"; `CopyPlanner` is the first.

**Recorded so the next coverage read does not treat them as gaps.** A second line of defence that
can be provoked is not a second line of defence. Making these reachable from a test would mean
widening the functions to `internal`, which buys a test that asserts an `error()` fires when called
in a way production cannot call it. This is the same judgement issue **#88** reached about
`getForegroundInfo` and closed on: naming the exemption rather than covering it.

Neither should change unless the upstream guard does. If `CopyPlanner` ever stops resolving `COPY`
before the builder sees it, `FFmpegCommandBuilder.kt:167` becomes live and wants a test that day.

---

## F5 — `ConversionNotifications.areEnabled()` is never called

**Severity: low · Confirmed by inspection · found while decomposing the test-gap ticket**

```
app/src/main/java/org/libremediaconverter/work/ConversionNotifications.kt:60-62
```

```kotlin
fun areEnabled(): Boolean = context.getSystemService(NotificationManager::class.java)
    .areNotificationsEnabled()
    .also { if (!it) Log.i(TAG, "Notifications disabled; progress will not be visible.") }
```

`grep -rn 'areEnabled' app/src` returns **that declaration and nothing else**. `ConversionNotifications`
is constructed in both workers (`ConversionWorker.kt:55`, `ConcatWorker.kt:35`) and only `build()` is
ever called on it.

**This entry exists because it was very nearly filed as a test gap.** Its three lines are cold on the
JVM, it has a KDoc explaining real user-visible stakes — a foreground service without
`POST_NOTIFICATIONS` shows only in the Task Manager, so progress silently vanishes — and Robolectric
can flip that permission in one line. Everything about it reads like a cheap, worthwhile test.

It is not, because **the behaviour the KDoc describes does not happen**. Nothing consults
`areEnabled()`, so nothing warns, degrades, or logs when notifications are off. A test would assert
that a function nobody calls returns what the platform told it — green, vacuous, and actively
misleading, since it would imply the app handles the disabled-notification case. That is the failure
mode `CLAUDE.md` records from the mutation review, reached from the opposite direction: not a test
that fails to bite, but a test with nothing to bite.

### What a fix has to decide

Whether the app should act on this at all. The KDoc argues it should — a conversion whose progress is
invisible is a real complaint, and `ConversionViewModel` or the worker's foreground start is where a
check would go. If yes, that is a **feature** with a test; if no, delete the method and the KDoc's
claim with it. What must not happen is a test that makes the current state look handled.

Related: **#16** is open on an adjacent gap — a user who *can* unblock a foreground-denied retry has
no way to make it happen now.

---

## Summary

| ID | Finding | Severity | Evidence | Action |
|---|---|---|---|---|
| F1 | `FFmpegCommandBuilder` emits a Vorbis encoder `ContainerCapabilities` says does not exist | low | confirmed by inspection; unreachability traced through four call sites | **decide**: feature or dead arm — the comment is false either way |
| F2 | `hardwareEncodeAvailable` written, never read; KDoc describes removed behaviour | low | confirmed by inspection; `FFmpegCommandBuilderTest:132` corroborates | **decide**: delete or mark vestigial |
| F3 | `ConversionRequest.videoCodec` / `.audioCodec` have no callers | low | confirmed by inspection | delete, or keep for symmetry — **not** a test gap |
| F4 | Two private guards reachable only by direct call | n/a | confirmed by inspection | **no action** — named exemption, per #88 |
| F5 | `ConversionNotifications.areEnabled()` is never called | low | confirmed by inspection; grep returns the declaration only | **decide**: act on it or delete it — **not** a test gap |

Order, if these are acted on: **F1 and F5 first, separately.** They are the two with a possible
user-visible answer — a format the app can produce and does not offer, and a warning the app
documents and does not give — and either answer changes what the tidying should look like. F2 and F3
are tidying and belong in one commit with each other, not with F1 or F5. F4 is finished by being
written down.

**F1 and F5 share a shape worth naming:** both are places where a comment describes behaviour the
code does not have, and in both the tempting fix (delete the dead arm, test the dead method) would
freeze the wrong answer in place. The decision comes first.

## Not covered here

**The test gaps from the same read.** Seven JVM-side gaps (**#132**) and three seam questions
(**#133**) came out of this coverage read and are tracked there, because they are work rather than
observations. This document holds only what a test would not fix. #133 also records why
`AndroidDeviceCodecs.probe()` was considered and left out, so that spike is not run a third time.

**`ConversionForegroundType.current()`**, which looked like the sharpest gap in the read and is not.
Its API 33 and 34 arms are cold on the JVM, but issue **#88** already established that the class is
covered by `ConversionWorkerTest.foregroundTypeMatchesTheRunningApiLevel` across the CI matrix, and
that its 0% is the `testDebugUnitTest`-only measurement boundary.

The premise worth re-checking was whether the 33/34 legs still complete, given #122's wedge.
**They mostly do, and #122 is not resolved** — this entry said "they do" on first writing, from a
single green run, and the PR carrying this very document proved that wrong:

| run | API 33 leg | shape |
|---|---|---|
| `32933262839` (#127) | success, 7m16s | `expected 60, received 60, failed 0, completed cleanly: yes` |
| `33033036857` (PR #131, docs-only) | **failure, 23m08s** | `expected 60, received 60, failed unknown, wedged: yes — gradle killed after 1200s` |

Five of the last six completed API 33 legs passed in about seven minutes, so the wedge is
intermittent rather than systematic. **What it costs is the verdict, not the execution**: `received:
60` on the wedged run means all sixty tests still reported, so the API 33 regime *was* exercised —
but `failed:` reads `unknown`, so that leg could not have told anyone if it had broken.

That is why this stays a note and not a ticket, and also why it is not simply deleted: #88's
reasoning holds, but the leg it rests on cannot be relied on to report a failure. A
`@Config(sdk = 33)` / `@Config(sdk = 34)` JVM test would pin all three arms deterministically in one
run for about three lines. Small, and worth doing the next time this file is opened — but it is
insurance against a flaky leg, not the uncovered behaviour it first looked like.

**The Compose screens' branch coverage.** `ConverterScreenKt` reports 110 of 200 branches missed and
`JoinScreenKt` 60 of 82, which looks alarming and is not a signal: the Compose compiler synthesises
`$changed`/`$dirty` recomposition-skip tests that JaCoCo counts as branches. The line figures are
the real ones — **34 of 383** and **20 of 143** missed — and the screens are among the
better-covered files in the repo, which is what #52, #57 and #61 were for. **Do not chase the
branch number here.** If a future read wants a screen metric, use lines.

**Anything requiring a device.** `MediaProbe`'s FFprobe half (`MediaProbe.kt:151, 156-158, 173-188`)
and `FFmpegEngine` in full report 0% on the JVM and are covered by `androidTest`. JaCoCo measures
`testDebugUnitTest` only; their zeroes are a boundary, as #84, #85, #86 and #88 each recorded
before this.
