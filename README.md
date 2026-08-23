# LibreMediaConverter

A free and open-source media converter for Android — batch video transcoding and
compression, audio extraction and conversion, GIF and frame export, and file merging.

Android 13+ (API 33). Built with Jetpack Compose and Material 3.

> **Status: working, unreleased.** Both conversion engines, the router, the background
> job queue and the join flow are implemented and building. The FFmpeg format tests run
> green on a physical Pixel 10 Pro XL (API 37) and on local emulators at API 33–36, and
> CI runs the instrumented suite at API 33–36 on every pull request.

*Correction (`R18 / #27`, 2026-08-22): this line used to say the FFmpeg format tests had "been
written but not yet executed on a device" — true when written, false from the first device pass,
and never updated. `FFmpegEngineTest`'s nine format tests were in every run named above and none
of them failed; the runs are recorded under "Verified on real API 37 hardware" in
[`docs/api-37-emulator-crash.md`](docs/api-37-emulator-crash.md) and "The sweep, run" in
[`docs/local-emulator.md`](docs/local-emulator.md). API 37 has no CI row — that emulator image is
broken — so it stays a manual Pixel check before each release.*

## Licensing at a glance

- **Source code: MIT**
- **Distributed APK: GPL-3.0** — because it bundles FFmpeg built with x264/x265

That split is deliberate, not an oversight. See [`LICENSES/README.md`](LICENSES/README.md)
for the reasoning and the corresponding-source obligations.

## Architecture

Two conversion engines behind an explicit router, because neither one covers the job alone.

### AndroidX Media3 Transformer — the hardware path

Handles the common cases: H.264/HEVC, resolution and frame-rate changes, rotation,
overlays, and audio to AAC. Fully hardware accelerated end to end — MediaCodec decodes to
a GL surface and MediaCodec re-encodes, so frames never round-trip through the CPU.
Roughly 7–8× realtime on 720p.

It writes **MP4 and nothing else**. `media3-muxer` ships WebM, Ogg, WAV and AAC muxers too,
but none can be driven by Transformer — they throw from `addMetadataEntry`, which the muxer
wrapper calls for every metadata entry a real recording carries. It *reads* far more than it
writes, Matroska included, which is what makes MKV → MP4 a hardware remux.

### FFmpeg — the long tail

Everything Media3 structurally cannot do:

- Containers outside MP4/WebM/Ogg/WAV/AAC — MKV, MOV, AVI, FLV, MPEG-TS, WMV/ASF
- **MP3 output** — Android has no MP3 encoder at any version; this is a platform gap
- GIF and image sequences
- Input codecs with no platform decoder on the device
- CRF and 2-pass rate control, for the quality tier
- Codecs Media3's muxers decline even on a stream copy — its MP4 muxer carries AAC, Opus,
  Vorbis and PCM, but neither MP3 nor FLAC

### Quality tiers

The router is surfaced to users as a quality choice rather than hidden:

| Tier | Engine | Rate control | Trade-off |
|---|---|---|---|
| **Fast** (default) | Media3 / MediaCodec | bitrate-targeted | ~7–8× realtime, low battery cost |
| **Best quality** | FFmpeg + x264/x265 | CRF or 2-pass | ~realtime or slower, better quality per byte |

## A note on "GPU acceleration"

Android has **no GPU video codec path**. There are three distinct tiers, and conflating
them causes a lot of confusion:

1. **Fixed-function video codec silicon** — reached through `MediaCodec`. This is what
   "hardware accelerated" means for encode and decode. It is not the GPU.
2. **GPU shader cores** — genuinely used, but only for filters, scaling, and color
   effects on already-decoded frames, via OpenGL ES. Never for entropy coding.
3. **CPU** — x264, x265, and software decoders.

FFmpeg's `-hwaccel` is meaningful on Android only as `mediacodec`, and even then it
targets direct-to-Surface playback rather than file-to-file transcoding. Vulkan Video
exists in FFmpeg 8.0+ but no shipping Android GPU driver exposes it — no `VK_KHR_video_*`
extension appears in any Android Vulkan Profile tier.

So this app is hardware accelerated via MediaCodec, and GPU accelerated for effects via
GL shaders. Both are real; neither is "the GPU decoding video."

## Remuxing

Changing the container without touching the streams. Copying an H.264 track from MKV into
MP4 moves the same samples into a different wrapper: it finishes in seconds instead of
minutes, costs no quality, and needs no encoder — which is why it stays on the hardware
path even on a device that cannot encode the codec in question.

`Copy` is a codec choice like any other, so it can be mixed: copy the video and re-encode
only the audio, or the reverse. Picking a codec the source already uses is upgraded to a
copy automatically **when the container is changing** — if container and codec both already
match, the only reason to run the job is to re-encode it, so it does.

A copy is never attempted on a stream whose codec could not be identified. A needless
re-encode costs time; a wrong stream copy costs a file that will not play.

## Features

| | Formats |
|---|---|
| Video out | MP4, MOV, MKV, WebM, MPEG-TS, AVI, FLV, WMV/ASF |
| Video codecs | H.264, H.265, VP9, or copy the source stream |
| Audio out | MP3, AAC/M4A, FLAC, Opus, WAV, MKA |
| Audio codecs | AAC, Opus, MP3, FLAC, PCM, or copy the source stream |
| Images | GIF, PNG frame sequences |
| Other | Remux without re-encoding; join several files into one |

Presets cover the common combinations in one tap. The Advanced picker exposes the full
container × codec matrix — including combinations that cannot work, which it explains and
offers alternatives for rather than hiding.

Conversions run as durable background work, so they survive leaving the app and are
restored after a restart.

## Building

Requires the Android SDK with API 37. **Do not pick a JDK** — the repo does.
`gradle/gradle-daemon-jvm.properties` pins the daemon to Java 25 and carries foojay
download URLs per platform, so Gradle finds an installed Java 25 or downloads one on the
first build, whatever `JAVA_HOME` points at. `JAVA_HOME` only chooses the *launcher*, which
Gradle 9.7.1 will run on Java 8 or newer. Everything the build actually compiles is Java 25,
the app's own bytecode included. `./gradlew --version` prints the launcher and the daemon
separately, and they routinely differ.

FFmpeg is committed as a prebuilt archive under [`bin/`](bin/README.md), so a clone
builds without a cross-compile. That is deliberate: rebuilding it per CI run made test
results ambiguous, because a red build could mean broken code or a build that hiccuped.
See [`bin/README.md`](bin/README.md) for its provenance and how to regenerate it.

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM tests
./gradlew :app:connectedDebugAndroidTest   # device tests, needs a running device
./gradlew :app:assembleRelease        # R8-minified release
```

See [`tools/ffmpeg/README.md`](tools/ffmpeg/README.md) for why the build is
containerised and which flags matter. That recipe remains the authority — the committed
archive is its output, and is also what satisfies the GPL corresponding-source
obligation.

## Testing

Unit tests cover the parts that decide correctness without needing hardware: the routing
matrix, the container × codec capability matrix, the FFmpeg argument builder, and both
stream-copy-versus-re-encode planners. They run against fabricated device profiles, so
branches like "this device cannot encode HEVC" are reachable regardless of what the test
machine is.

Instrumented tests cover the parts that only a device can prove: real hardware
transcoding, the foreground service type, and each FFmpeg output format asserted against
the produced file rather than the exit code. The remux tests additionally assert which
*engine* ran — a stream copy produces an identical file either way, so an output-only
assertion cannot tell a hardware transmux from FFmpeg's `-c copy`.

## Privacy

The app has **no `INTERNET` permission**, so it cannot open a network connection at all.
Nothing is uploaded, and there is no analytics or advertising. See [PRIVACY.md](PRIVACY.md),
which also explains the permissions WorkManager adds automatically.

## Contributing

Contributions are welcome. Note that contributions to the source are under MIT, while
the distributed binary remains GPL-3.0 for the reasons described in `LICENSES/README.md`.
