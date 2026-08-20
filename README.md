# Media Converter

A free and open-source media converter for Android — batch video transcoding and
compression, audio extraction and conversion, GIF and frame export, and file merging.

Android 13+ (API 33). Built with Jetpack Compose and Material 3.

> **Status: early development.** The project scaffold and UI shell exist; the conversion
> pipeline is being built out. Not yet usable.

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

## Building

Requires JDK 17+ and the Android SDK with API 37.

```
./gradlew :app:assembleDebug
```

The FFmpeg native library is built separately from source; that build is not yet wired
into this repository.

## Contributing

Contributions are welcome. Note that contributions to the source are under MIT, while
the distributed binary remains GPL-3.0 for the reasons described in `LICENSES/README.md`.
