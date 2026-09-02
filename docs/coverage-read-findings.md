# Coverage-read findings

**Status:** ten findings, none fixed, none urgent. F1-F4 came from the 2026-08-26 read; F5 was added
on 2026-08-27 while decomposing #132; **F6-F10 were added on 2026-09-02 from the wave-4 read**. Every
entry here is a *code* observation — something a test would document rather than repair. The test
gaps found in the same reads are tickets, not entries here; see [Not covered here](#not-covered-here).
**Scope:** what a JaCoCo read turned up that writing a test would not fix. This is a survey, not a
work order. Acting on any entry is a separate decision and would be its own commit.
**Last verified:** `main` at `54ca2dd`, 2026-09-02. Coverage measured that day with
`./gradlew :app:jacocoTestReport`: **92.8% line (2183/2352), 81.3% branch (1091/1342)**, against
**584 JVM tests in 87 classes**, matching what `CLAUDE.md` quotes.

The wave-4 read that produced F6-F10 also produced twelve test tickets, **#192-#203**, plus **#204**
for four candidates whose cost was not obviously worth paying. The split between them is the same one
this document has always drawn: a ticket is where a test goes, an entry here is where a test would not
help.

## Why this document is separate from `defect-audit.md`

`defect-audit.md` is the record of the 2026-08-22 defect sweep: sixteen entries, each a thing that
is *wrong at runtime*. Nothing here is wrong at runtime today. These are arms that cannot be
reached, accessors nobody calls, and two KDocs that contradict the code beside them — the category
`defect-audit.md` calls **latent**, plus several that are not defects at all and are recorded so the
next coverage read does not re-file them.

They are here rather than in that document because folding them in would inflate a sixteen-entry
audit whose status metadata has already gone stale once, and because they share a provenance:
every one fell out of reading a coverage report, and every one is the kind of thing a coverage
report is *good* at surfacing and a test is bad at fixing. F5 is the clearest case — it was filed
as a test gap first, and only stopped being one when someone went looking for its callers.

Entry ids are `F1`–`F10` so they cannot be confused with `defect-audit.md`'s `D1`–`D16`.

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

## F6 — Four more arms that cannot be reached, and one KDoc among them that is false

**Severity: low · Confirmed by inspection · F4's family, found in the wave-4 read**

```
app/src/main/java/org/libremediaconverter/model/ConversionRouter.kt:178-179
app/src/main/java/org/libremediaconverter/model/ConversionRouter.kt:221
app/src/main/java/org/libremediaconverter/model/ContainerCapabilities.kt:297
app/src/main/java/org/libremediaconverter/model/ContainerCapabilities.kt:324, :340
```

Four sites that a coverage report flags and that no test can reach. Each is recorded with the
upstream guard that makes it unreachable, because that guard is what would have to change first.

- **`ConversionRouter:178-179`** — the missed branch is `orEmpty()`'s absent-key arm on
  `MEDIA3_MUXABLE_VIDEO[plan.container]`. `MEDIA3_CONTAINERS` is `setOf(MP4)` and `route()` returns at
  `:104` for anything else, so `media3CanMux` only ever sees MP4, which both maps key. Same function
  as F4's second pair, one line below it.
- **`ConversionRouter:221`** — `DeviceCodecs.PERMISSIVE.canDecode` returning **false** for
  `InputProbe.UNPARSEABLE`. `PERMISSIVE` has no production caller at all (tests only), and the
  router's one `canDecode` call at `:128` is already preceded by `:117` returning FFMPEG for
  `UNPARSEABLE`. **Its KDoc at `:214-217` is false as written:**

  > That exception matters: a device double that claims it can decode an unparseable file would let
  > the router send a doomed job to Media3.

  It would not — `:117` already caught it. This is F2's shape: a comment that describes a hazard the
  code upstream has removed. Correcting it is a one-line change and should not be bundled with
  anything.
- **`ContainerCapabilities:297`** — `if (container == GIF || container == IMAGE_SEQUENCE) return null`
  in `repair`. `repair`'s only caller is `suggestions` (`:281`); `validate` returns at `:121` for
  `isImageOutput` (which is exactly GIF ∥ IMAGE_SEQUENCE) before `suggestions` is reached, and
  `firstContainerHolding` filters on `CARRIES_VIDEO`, which is empty for both.
- **`ContainerCapabilities:324` and `:340`** — the `else ->` arms themselves are exercised; what is
  missed is the elvis tail, `firstOrNull() ?: VideoCodec.NONE` / `?: AudioCodec.NONE`. Reaching it
  needs a container with no encodable codec on that axis. Audio-only containers return early at
  `:307`, and the only containers with an empty audio set are GIF and IMAGE_SEQUENCE, excluded at
  `:297` above.

**Recorded so the next read does not re-file them.** F4's rule applies unchanged: a second line of
defence that can be provoked is not a second line of defence, and widening a private function to make
one reachable buys a test that asserts a fallback fires when called in a way production cannot call
it.

---

## F7 — `probeWithExtractor`'s catch is unreachable for the same measured reason `probeForConcat`'s is

**Severity: n/a · No action · completes a measurement already on record**

```
app/src/main/java/org/libremediaconverter/convert/MediaProbe.kt:180-182
```

```kotlin
} catch (e: Exception) {
    Log.i(TAG, "Platform extractor could not read $uri.", e)
    null
}
```

`CLAUDE.md` records the measurement for the *other* extractor site: Robolectric's `MediaExtractor`
never throws from `setDataSource`, checked across an unregistered `content://` authority, a missing
`file://`, a file of garbage bytes and an `http://` URL — all four returned with `trackCount = 0`.

`probeWithExtractor` calls the same overload, three lines apart in the same file, and the measurement
covers it identically. It was simply not written down for this site, so a future read would re-derive
it. It stays device-only, alongside `probeForConcat`'s.

**Two neighbouring line counts are artifacts of this, not separate gaps.** `MediaProbe:184` and
`:331` each report 27 missed instructions and are the `finally` block's synthetic exception-path copy
— JaCoCo duplicates a `finally` per exit path, and the exceptional one is unreachable for the reason
above. Do not read them as a third and fourth site.

---

## F8 — Three more dead members, and six unused defaults

**Severity: low · Confirmed by inspection · F3's family**

```
app/src/main/java/org/libremediaconverter/model/CopyPlanner.kt:28          ConversionPlan.hasVideo
app/src/main/java/org/libremediaconverter/codec/AndroidDeviceCodecs.kt:39  hardwareEncoders()
app/src/main/java/org/libremediaconverter/ffmpeg/ConcatEngine.kt:30        Result.output
app/src/main/java/org/libremediaconverter/convert/Transcoders.kt:28, :29, :40, :61
app/src/main/java/org/libremediaconverter/work/Reattachment.kt:28, :30
```

- **`ConversionPlan.hasVideo`** — zero callers in `main`, `test` or `androidTest`. Every `hasVideo`
  hit in the tree is `InputProbe.hasVideo`, `OutputSpec.hasVideo` or `Container.extensionFor(hasVideo)`,
  which are different properties on different types. A test asserting
  `plan.hasVideo == (plan.video != VideoPlan.Drop)` is vacuous by construction.
- **`AndroidDeviceCodecs.hardwareEncoders()`** — its only caller is `RealMediaBenchmark`, in
  `androidTest`. Production reads capabilities through `DeviceCodecs`, never the raw set.
- **`ConcatEngine.Result.output`** — `ConcatWorker` reads `result.strategy` and uses the `staged`
  file it passed in, never `.output`.
- **`Transcoders.kt`'s default arguments** — `request` and `onProgress` on
  `HardwareTranscoder.transcode` (`:28`, `:29`), `onProgress` on `SoftwareTranscoder.run` (`:40`),
  and `format` on `ConcatJoiner.join` (`:61`). All three production call sites
  (`ConversionWorker.kt:208`, `:234`, `ConcatWorker.kt:79`) pass every argument, so the synthesised
  `$default` bridges and `$DefaultImpls` copies are never entered. The
  `request: ConversionRequest = ConversionRequest(OutputFormat.MP4_H265.spec)` default is the one
  worth a second look: nothing anywhere omits it, so an interface silently promises H.265 to a
  caller that does not exist.
- **`JobSnapshot`'s `outputModifiedAt` and `tags` defaults** — `JobSnapshots.kt:32-42` passes all
  seven fields, so the synthesised `$default` constructor (20 missed instructions at
  `Reattachment.kt:14`) is never entered.

**Not a test gap, for F3's reason.** Delete them, or keep them and know they are unused; either is a
decision, and a test restating the compiler is not.

---

## F9 — Both workers' `getForegroundInfo` overrides are dead, and this is why

**Severity: n/a · No action · sharpens #88 rather than reopening it**

```
app/src/main/java/org/libremediaconverter/work/ConversionWorker.kt:342-346
app/src/main/java/org/libremediaconverter/work/ConcatWorker.kt:132-136
```

**#88 already closed on these**, after reading both and finding no decision worth a seam — the
correct call, and it stands. What #88 did not name is the reason they are cold in the first place,
which is stronger than "the JVM cannot reach them":

WorkManager calls `getForegroundInfoAsync()` **only for expedited work**. `ConversionWorker`'s own
KDoc says expedited is deliberately not used, and `grep -rn 'setExpedited\|OutOfQuotaPolicy' app/src`
returns nothing. So both overrides are dead in production today, not merely untested — a test would
assert the shape of something nothing invokes.

They are still correct to keep: `ForegroundInfo` is required by the `CoroutineWorker` contract and
`setForeground` is called explicitly elsewhere. **What would reopen this** is the same trigger #88
named — a `getForegroundInfo` that starts branching — plus one more: the day anything calls
`setExpedited`.

---

## F10 — Three arms that are reachable, uncovered, and cannot be made to bite

**Severity: n/a · No action · the shape a coverage number cannot distinguish**

```
app/src/main/java/org/libremediaconverter/convert/ConversionViewModel.kt:550, :553
app/src/main/java/org/libremediaconverter/join/JoinViewModel.kt:349, :352, :278
```

F4 and F6 hold arms that cannot be *reached*. These can — and a test written against them would still
pass under the mutation that ought to redden it, which is the harder case to spot and the more
expensive one to discover halfway through writing the test.

- **`observer?.cancel()`'s non-null arm** (`ConversionViewModel:550`, `JoinViewModel:349`). Reachable
  by calling `convert()` twice. But `ScreenOwnership`'s token is what actually blocks the superseded
  write — the ViewModel's own KDoc at `reset()` says the cancel is "a request honoured at the next
  suspension point" and "the claim is what actually stops that write". Delete `observer?.cancel()`
  and the suite stays green, correctly.
- **`if (info == null) return@collect`** (`ConversionViewModel:553`, `JoinViewModel:352`). Reachable
  through `pruneWork()`. But when the null arrives the state is already terminal, so removing the
  guard crashes the collector and **leaves the state unchanged** — a state assertion is green under
  the mutation. The only observable is an escaped coroutine exception, which the ViewModel's own KDoc
  documents as unreliable on the JVM: kotlinx-coroutines-test's process-wide collector hands it to
  whichever `runTest` starts next.
- **`JoinViewModel:278`'s `Ambiguous` arm.** Looks like the twin of `ReattachGuardsTest`'s "a result
  two jobs both claim", and is not. An `Ambiguous` requires a shared `outputPath`, so it can only be a
  *finished* job — which maps to `Joined`, a state that reads nothing from `inputs`. **The Convert-side
  twin does bite**, because `displayNameOf(tags)` reaches the file card; the asymmetry is the point.

**Recorded because each of these was picked up as a candidate and put down again.** The wave-4 read
lost time to all three before the mutation test was run in the head rather than the editor, which is
the cheaper order.

---

## Summary

| ID | Finding | Severity | Evidence | Action |
|---|---|---|---|---|
| F1 | `FFmpegCommandBuilder` emits a Vorbis encoder `ContainerCapabilities` says does not exist | low | confirmed by inspection; unreachability traced through four call sites | **decide**: feature or dead arm — the comment is false either way |
| F2 | `hardwareEncodeAvailable` written, never read; KDoc describes removed behaviour | low | confirmed by inspection; `FFmpegCommandBuilderTest:132` corroborates | **decide**: delete or mark vestigial |
| F3 | `ConversionRequest.videoCodec` / `.audioCodec` have no callers | low | confirmed by inspection | delete, or keep for symmetry — **not** a test gap |
| F4 | Two private guards reachable only by direct call | n/a | confirmed by inspection | **no action** — named exemption, per #88 |
| F5 | `ConversionNotifications.areEnabled()` is never called | low | confirmed by inspection; grep returns the declaration only | **decide**: act on it or delete it — **not** a test gap |
| F6 | Four more unreachable arms; `ConversionRouter:214-217`'s KDoc is false | low | confirmed by inspection; each traced to its upstream guard | **no action**, except the one-line KDoc fix |
| F7 | `probeWithExtractor`'s catch is unreachable, as `probeForConcat`'s is | n/a | measured across four URI shapes (recorded in `CLAUDE.md`) | **no action** — device-only, now written down for both sites |
| F8 | Three more dead members and six unused defaults | low | confirmed by inspection; grep per member | delete or keep knowingly — **not** a test gap |
| F9 | Both `getForegroundInfo` overrides are dead: expedited work is never used | n/a | confirmed by inspection; `grep setExpedited` returns nothing | **no action** — sharpens #88's close |
| F10 | Three reachable arms where no mutation bites | n/a | confirmed by inspection; each mutation traced to its masking guard | **no action** — recorded to stop the next read re-picking them |

Order, if these are acted on: **F1 and F5 first, separately.** They are the two with a possible
user-visible answer — a format the app can produce and does not offer, and a warning the app
documents and does not give — and either answer changes what the tidying should look like. F2, F3 and
F8 are tidying and belong in one commit with each other, not with F1 or F5. F6's KDoc correction is a
third kind: one line, no decision, and it should not wait on the tidying. F4, F7, F9 and F10 are
finished by being written down.

**Six of the ten are now "no action" or "not a test gap", and that is the useful shape.** By wave 4
the report's remaining red is mostly this: arms nothing can reach, members nothing calls, and arms a
test can reach but not pin. A coverage number cannot tell any of them from a real gap, which is why
this document exists and why it grows faster than the percentage moves.

**F1 and F5 share a shape worth naming:** both are places where a comment describes behaviour the
code does not have, and in both the tempting fix (delete the dead arm, test the dead method) would
freeze the wrong answer in place. The decision comes first.

## Not covered here

**The test gaps from the same read.** Seven JVM-side gaps (**#132**) and three seam questions
(**#133**) came out of this coverage read and are tracked there, because they are work rather than
observations. This document holds only what a test would not fix. #133 also records why
`AndroidDeviceCodecs.probe()` was considered and left out **through `ShadowMediaCodecList`**, so that
spike is not run a third time.

**Updated 2026-09-02:** #194 proposes reaching the same code through a *pure seam* instead, which is a
different mechanism and one #133 did not evaluate — the builder objection it turns on (no
`setIsAlias`, no `setCanonicalName`) does not apply to a function taking its own entry type. #133's
close stands for the shadow; it is not a close on the seam. #194 also carries the reason the seam is
worth cutting at all, which is not coverage: the `runCatching` fallback logs "assuming permissive" and
returns empty sets, which makes `canEncode` and `canDecode` answer *no* for everything.

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

**Updated 2026-09-02: the same codegen inflates the *instruction* count, which wave 3's filter did
not allow for.** Wave 3 selected candidates on `mi > 0` — at least one missed instruction — which was
right to prefer over a bare branch count and is still wrong on these files. `JoinScreen.kt:222` reads
`mi=10` and looks uncovered; it also reads `ci=38`, and `JoinStateAffordancesTest` already clicks that
Save button and asserts `save:joined.mp4`. Every `onClick` lambda body flagged this way turned out to
be covered at method level, the missed instructions being the recomposition-skip path again.

Use `ci == 0` — the line never executed, which is JaCoCo's own missed-line definition — and pair it
with a method-level `ci > 0 && mb > 0` pass for covered methods with cold arms. Neither filter alone
is enough: `ConversionViewModel.cancel()` misses no line at all, yet its non-null arm had never been
entered in 584 tests (#192). `CLAUDE.md`'s coverage entry carries the same correction.

**Also codegen, also not gaps**, recorded once so they are not re-derived: the synthetic
`NoWhenBranchMatchedException` closing an exhaustive `when` (`ConverterScreen:399`, `:686`,
`JoinScreen:278`, `MainActivity:160`); the inner `is Idle -> Unit` arms at `ConverterScreen:253-254`
and `JoinScreen:158-159`, which are structurally unreachable because the outer `when` already routed
`Idle`; and the closing brace of a `launch` block whose `collect` never terminates
(`ConversionViewModel:578`, `JoinViewModel:371`).

**Anything requiring a device.** `MediaProbe`'s FFprobe half (`MediaProbe.kt:151, 156-158, 173-188`)
and `FFmpegEngine` in full report 0% on the JVM and are covered by `androidTest`. JaCoCo measures
`testDebugUnitTest` only; their zeroes are a boundary, as #84, #85, #86 and #88 each recorded
before this.
