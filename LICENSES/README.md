# Licensing

This project has a **split license**, and the distinction matters.

## Source code — MIT

Everything in this repository that we wrote is MIT licensed. See [`../LICENSE`](../LICENSE).

## Distributed binary (APK / AAB) — GPL-3.0

The shipped application binary is **GPL-3.0**, not MIT.

The app bundles FFmpeg built with `--enable-gpl`, which pulls in **x264** and **x265**
(both GPL-2.0-or-later). Combining those with the application makes the *distributed
binary* a GPL work. MIT → GPL is a compatible direction, so our own source stays MIT;
the combined binary you install is GPL-3.0.

### Why GPL rather than LGPL

Building without `--enable-gpl` would keep the binary LGPL, and most features survive
that: hardware H.264/HEVC encode via MediaCodec, MP3 via libmp3lame, AV1, VP9, Opus,
FLAC, and even subtitle burn-in (libass is ISC licensed, not GPL — a common
misconception).

What GPL specifically buys is **x264/x265 software encode**, which is the only way to
get CRF and 2-pass rate control. Those are the tools that actually deliver
quality-per-bitrate for "compress this video well", and no hardware encoder on Android
exposes either. That capability was judged worth the license.

### Corresponding source

GPL-3.0 requires that complete corresponding source accompany the binary. For each
release we publish, alongside the APK:

- the exact FFmpeg source tarball or commit used,
- the full `configure` line, and
- any patches applied.

FFmpeg's own guidance says to host the source "on the same webserver" as the binary.
That is not literally possible for a Google Play listing, so the source is attached to
the corresponding **GitHub Release next to the APK**, and linked from both the Play
listing and the in-app About screen.

## Third-party components

| Component | License | Notes |
|---|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 | |
| AndroidX Media3 (Transformer, Effect) | Apache-2.0 | primary hardware conversion path |
| FFmpeg | LGPL-2.1+, **GPL-2.0+ as built here** | `--enable-gpl` |
| x264 | GPL-2.0+ | the reason the binary is GPL |
| x265 | GPL-2.0+ | |
| libass | **ISC** | subtitle burn-in; not GPL |
| libmp3lame | LGPL | MP3 encode |
| libvpx, dav1d, SVT-AV1, libopus | BSD-style | |

### Explicitly excluded

- **`--enable-nonfree`** is never used. FFmpeg states it makes the resulting binary
  *unredistributable*. This rules out `libfdk_aac` and `decklink`.
- **OpenH264** is not used. Its BSD-2 source license is *not* a patent grant — Cisco's
  royalty payment covers only Cisco's own precompiled binary module.
