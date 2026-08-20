# LibreMediaConverter

A free and open-source media converter for Android — batch video transcoding and
compression, audio extraction and conversion, GIF and frame export, and file merging.

Android 13+ (API 33). Built with Jetpack Compose and Material 3.

> **Status: working, unreleased.** Both conversion engines, the router, the background
> job queue and the join flow are implemented and building. The FFmpeg format tests have
> been written but not yet executed on a device.

## Licensing at a glance

- **Source code: MIT**
- **Distributed APK: GPL-3.0** — because it bundles FFmpeg built with x264/x265

That split is deliberate, not an oversight. See [`LICENSES/README.md`](LICENSES/README.md)
for the reasoning and the corresponding-source obligations.

## Architecture

Two conversion engines behind an explicit router, because neither one covers the job alone.

### AndroidX Media3 Transformer — the hardware path

Handles the common cases: MP4/MOV in and out, H.264/HEVC, resolution and frame-rate
changes, rotation, overlays, audio to AAC, and stream-copy transmuxing. Fully hardware
accelerated end to end — MediaCodec decodes to a GL surface and MediaCodec re-encodes,
so frames never round-trip through the CPU. Roughly 7–8× realtime on 720p.

### FFmpeg — the long tail

Everything Media3 structurally cannot do:

- Containers outside MP4/WebM/Ogg/WAV/AAC — MKV, AVI, FLV, MPEG-TS
- **MP3 output** — Android has no MP3 encoder at any version; this is a platform gap
- GIF and image sequences
- Input codecs with no platform decoder on the device
- CRF and 2-pass rate control, for the quality tier

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

## Features

| | Formats |
|---|---|
| Video out | MP4 (H.264/H.265), MKV (H.264/H.265), WebM (VP9) |
| Audio out | MP3, AAC/M4A, FLAC, Opus, WAV |
| Images | GIF, PNG frame sequences |
| Other | Join several files into one |

Conversions run as durable background work, so they survive leaving the app and are
restored after a restart.

## Building

Requires JDK 17+ (AGP 9 will not run on older) and the Android SDK with API 37.

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

Unit tests cover the parts that decide correctness without needing hardware: the
routing matrix, the FFmpeg argument builder, and the stream-copy-versus-re-encode
planner. They run against fabricated device profiles, so branches like "this device
cannot encode HEVC" are reachable regardless of what the test machine is.

Instrumented tests cover the parts that only a device can prove: real hardware
transcoding, the foreground service type, and each FFmpeg output format asserted
against the produced file rather than the exit code.

## Privacy

The app has **no `INTERNET` permission**, so it cannot open a network connection at all.
Nothing is uploaded, and there is no analytics or advertising. See [PRIVACY.md](PRIVACY.md),
which also explains the permissions WorkManager adds automatically.

## Contributing

Contributions are welcome. Note that contributions to the source are under MIT, while
the distributed binary remains GPL-3.0 for the reasons described in `LICENSES/README.md`.
