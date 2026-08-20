#!/usr/bin/env bash
#
# Builds ffmpeg-kit-next into an Android AAR. Runs INSIDE the container image
# defined by the sibling Containerfile.
#
# Usage:  build-ffmpeg.sh [spike|full]
#
#   spike - minimal library set. Validates that the toolchain works and produces an
#           AAR, without waiting on x264/x265/SVT-AV1. Use this first.
#   full  - the shipping configuration (GPL: x264 + x265).
#
set -euo pipefail

# The upstream scripts use `#!/bin/bash`, but the nixos/nix image ships only /bin/sh
# (itself bash, via the Nix store). Without this, start-android.sh dies with
# "cannot execute: required file not found" AFTER the whole toolchain has been built,
# which is an expensive way to discover a missing symlink.
if [[ ! -e /bin/bash ]]; then
  ln -sf "$(command -v bash)" /bin/bash
fi

MODE="${1:-spike}"
TAG="${FFMPEG_KIT_TAG:-v8.1.1}"
SRC=/work/ffmpeg-kit-next
OUT=/work/out

# ---------------------------------------------------------------------------
# Library selection
# ---------------------------------------------------------------------------
# Flag names come from get_library_name() in scripts/function.sh — note it is
# --enable-lame, NOT --enable-libmp3lame.
#
# android-media-codec gives FFmpeg the h264_mediacodec / hevc_mediacodec wrappers.
# Those are the fallback-within-the-fallback: hardware encode from the FFmpeg side
# when a job has been routed away from Media3 for container reasons but still wants
# hardware encode.

COMMON_LIBS=(
  --enable-android-media-codec
  --enable-android-zlib
  --enable-lame          # MP3 encode. Android has NO MP3 encoder at any API level,
                         # so this is the only way the app can output MP3 at all.
  --enable-opus
  --enable-dav1d         # fast AV1 decode
)

SUBTITLE_LIBS=(
  --enable-libass        # ISC licensed, NOT GPL - subtitle burn-in is LGPL-safe
  --enable-fontconfig
  --enable-freetype
  --enable-fribidi
  --enable-harfbuzz
)

# GPL. These are the reason the shipped binary is GPL-3.0 rather than LGPL: they are
# the only route to CRF and 2-pass rate control, which no Android hardware encoder
# exposes. See LICENSES/README.md.
GPL_LIBS=(
  --enable-gpl
  --enable-x264
  --enable-x265
)

EXTRA_LIBS=(
  --enable-libvpx        # VP8/VP9
  --enable-libsvtav1     # fast AV1 encode
)

case "$MODE" in
  spike) LIBS=("${COMMON_LIBS[@]}") ;;
  full)  LIBS=("${COMMON_LIBS[@]}" "${SUBTITLE_LIBS[@]}" "${GPL_LIBS[@]}" "${EXTRA_LIBS[@]}") ;;
  *) echo "unknown mode: $MODE (expected 'spike' or 'full')" >&2; exit 2 ;;
esac

echo "=============================================="
echo " ffmpeg-kit-next build"
echo " mode      : $MODE"
echo " tag       : $TAG"
echo " libraries : ${LIBS[*]}"
echo " started   : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=============================================="

if [[ ! -d "$SRC" ]]; then
  git clone --branch "$TAG" --depth 1 \
      https://github.com/arthenica/ffmpeg-kit-next.git "$SRC"
fi

cd "$SRC"

# ---------------------------------------------------------------------------
# AAPT2 override
# ---------------------------------------------------------------------------
# The final step packages the .so files into an AAR with Gradle. Gradle's default
# AAPT2 comes from Maven as a prebuilt binary dynamically linked against normal FHS
# paths (/lib64/ld-linux-x86-64.so.2). Those do not exist in a Nix image, so it dies
# with "AAPT2 ... Daemon startup failed" AFTER the entire native build has succeeded.
#
# The Android SDK that Nix provides has an aapt2 that nixpkgs has already patchelf'd,
# so point Gradle at that one instead.
AAPT2="$(find /nix/store -maxdepth 6 -name aapt2 -type f 2>/dev/null | head -1)"
if [[ -n "$AAPT2" ]]; then
  echo "using nix-provided aapt2: $AAPT2"
  grep -v 'aapt2FromMavenOverride' android/gradle.properties > /tmp/gradle.properties.new || true
  mv /tmp/gradle.properties.new android/gradle.properties
  echo "android.aapt2FromMavenOverride=$AAPT2" >> android/gradle.properties
else
  echo "WARNING: no nix aapt2 found; the AAR packaging step will probably fail." >&2
fi

# 64-bit only, matching the app's abiFilters. Dropping the 32-bit ABIs roughly halves
# build time and APK size, and Play does not require them.
# --api-level matches the app's minSdk 33 (default is 24), so the native code may use
# the newer NDK media APIs. ffmpeg-kit protocols (ffkitsaf, ffkitmem, ffkitstream) are
# left enabled: ffkitsaf is the SAF bridge that replaces the old getSafParameter trick.
# Output still stages through a real cache path rather than a SAF fd, because MP4
# faststart needs to seek back to rewrite the moov atom.
./nix-android.sh -p android-r27d \
  --api-level=33 \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  "${LIBS[@]}"

mkdir -p "$OUT"
# Only the ffmpeg-kit AAR. A bare '*.aar' find also sweeps up every AAR that Gradle
# happens to have unpacked into its own caches (junit, espresso, tracing...), which
# is confusing noise in the output directory.
find "$SRC/android/ffmpeg-kit-next-android-lib/build/outputs/aar" \
     "$SRC/prebuilt" \
     -name 'ffmpeg-kit-next*.aar' -exec cp -v {} "$OUT/" \; 2>/dev/null

echo "=============================================="
echo " finished : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
ls -la "$OUT" || true
