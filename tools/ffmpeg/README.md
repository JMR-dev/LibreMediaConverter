# FFmpeg build

Builds [`ffmpeg-kit-next`](https://github.com/arthenica/ffmpeg-kit-next) into an Android
AAR that the app consumes.

## Why this exists

`arthenica/ffmpeg-kit` — the library nearly every Android FFmpeg tutorial still points at
— was **archived**, and its binaries were **deleted from Maven Central**. Every
`com.arthenica:ffmpeg-kit-*` coordinate now returns 404, and all of its GitHub release
tags have zero attached assets. Maven Central's search index still *lists* the old
versions, which is misleading; the files behind those entries are gone.

Its successor, `ffmpeg-kit-next`, is **source-only by design** and publishes no
prebuilt packages. So building FFmpeg ourselves is not a preference, it is the only
remaining option.

Community forks publishing prebuilt 16 KB-aligned AARs do exist, but each fails on
license, ABI coverage, or publisher credibility — and at least one redistributes a
non-free FDK-AAC build, which FFmpeg states is *unredistributable*.

## Why a container

`ffmpeg-kit-next` is **Nix-only**. There is no plain `android.sh`; the repository ships
`nix-android.sh` plus a flake, and the flake pins the entire toolchain including
**Android NDK 27.3.13750724 (r27d)**.

Rather than install Nix on a developer machine, the toolchain lives in a container
image. That keeps the host clean and doubles as the reproducibility artifact F-Droid
wants.

Note the NDK version: **do not** "helpfully" upgrade to r28+. The flake pins r27d and
`android/jni/Android.mk` applies `-Wl,-z,max-page-size=16384` manually for `arm64-v8a`
and `x86_64` precisely because r27 predates automatic 16 KB alignment. The output is
16 KB compliant as-is.

## Usage

```sh
podman build -t ffmpeg-kit-builder:local -f Containerfile .

mkdir -p out
podman run --name ffmpeg-build -v "$PWD/out":/work/out:Z \
    localhost/ffmpeg-kit-builder:local full
```

Two modes:

| Mode | Libraries | Purpose |
|---|---|---|
| `spike` | minimal | Validates the toolchain end to end without waiting on x264/x265/SVT-AV1 |
| `full` | shipping set | The GPL configuration that ships |

The run deliberately omits `--rm`: the container's writable layer retains the several
gigabytes of Nix store contents (Android SDK and NDK), so subsequent builds skip the
download. Reuse it with `podman start -a ffmpeg-build`.

Only `arm64-v8a` and `x86_64` are built, matching the app's `abiFilters`. Dropping the
32-bit ABIs roughly halves both build time and APK size, and Play does not require them.

## Library selection

Flag names come from `get_library_name()` in the upstream `scripts/function.sh`. Two
that are easy to get wrong:

- It is **`--enable-lame`**, not `--enable-libmp3lame`.
- It is **`--enable-libsvtav1`** for SVT-AV1.

MP3 deserves a note: **Android has no MP3 encoder at any API level**. That is a platform
gap, not a Media3 limitation, so `--enable-lame` is the only way the app can output MP3.

`--enable-android-media-codec` gives FFmpeg the `h264_mediacodec` / `hevc_mediacodec`
wrappers (added in FFmpeg 6.0). These act as a fallback-within-the-fallback: hardware
encode from the FFmpeg side when a job was routed away from Media3 for container reasons
but still wants hardware speed.

## Licensing

The `full` build passes `--enable-gpl` with **x264** and **x265**, which makes the
distributed binary **GPL-3.0**. This is deliberate — see [`../../LICENSES/README.md`](../../LICENSES/README.md).

Worth recording, because it is widely misunderstood: **libass is ISC licensed, not GPL**,
so subtitle burn-in does not require the GPL flag. The only things GPL genuinely buys are
x264/x265 software encode (and with them CRF and 2-pass rate control), vidstab, and the
GPL filter set.

Never build with `--enable-nonfree`. FFmpeg states it renders the binary
*unredistributable*.

## Verified build output (2026-08-19, ffmpeg-kit-next v8.1.1 / FFmpeg 8.1.2)

The `full` build produced a 35 MB AAR with 10 shared libraries per ABI for `arm64-v8a`
and `x86_64`. Confirmed against the artifact rather than assumed:

- **16 KB page alignment**: every `.so` on both ABIs reports `LOAD align 0x4000`
  (`readelf -lW`). This is the hard Play gate.
- **Separate shared libraries**, not a static monolith — which is what the LGPL/GPL
  relinking obligation requires.
- **Embedded configure line** (from `strings libavutil.so`):
  `--enable-gpl --enable-version3 --enable-libx264 --enable-libx265 --enable-libsvtav1
  --enable-libvpx --enable-libmp3lame --enable-libopus --enable-libdav1d --enable-libass
  --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libharfbuzz
  --enable-mediacodec --enable-jni --enable-shared --enable-small --enable-lto`
- Present and verified: `libx264` (with an x264 core banner, so genuinely linked),
  `libx265`, `libsvtav1`, `libmp3lame`, `h264_mediacodec`, `hevc_mediacodec`, `libopus`,
  `libdav1d`, the GIF encoder and muxer, libass internals (`ass_shaper_new`), and the
  `subtitles`, `scale`, `palettegen`, `paletteuse` and `concat` filters.

Note `--enable-version3`: combined with `--enable-gpl` this makes the binary **GPL-3.0**,
which is what `LICENSES/README.md` states.

A caution on verifying this yourself: the build uses `--enable-small` and `--enable-lto`,
so internal symbols like `ff_libx264_encoder` do **not** appear in `strings` output.
Their absence proves nothing. Check the configure line and the registered codec *names*
instead.

## Release checklist

GPL-3.0 requires corresponding source alongside the binary. For each release, attach to
the GitHub Release next to the APK:

- the exact `ffmpeg-kit-next` tag and FFmpeg version used,
- the full configure line (printed in this script's build log), and
- any patches applied.
