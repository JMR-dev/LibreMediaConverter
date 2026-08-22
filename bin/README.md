# Prebuilt FFmpeg

`ffmpeg-kit-next-8.1.1.aar` is committed here deliberately, and this file records what
it is so the binary is auditable rather than opaque.

## Why it is committed

Tests must be deterministic. When CI rebuilt FFmpeg on every run, a red build could mean
"the code is broken" or "a 40-minute cross-compile hiccuped", and those are not the same
signal. Committing the artifact removes the second possibility entirely: a test run
either passes or points at real code.

It also removes roughly forty minutes from every cold CI run.

## Provenance

| | |
|---|---|
| Upstream | [arthenica/ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next) v8.1.1 |
| FFmpeg | 8.1.2 |
| NDK | r27d (27.3.13750724), pinned by the upstream flake |
| API level | 33, matching the app's minSdk |
| ABIs | arm64-v8a, x86_64 |
| Shared libraries | 20 (10 per ABI) |
| SHA-256 | `ae188c9aec3c89a1c87a169589253c85438d57cfdcc3ce8b40fb3e87de368ff2` |

Configure line, read back out of the shipped `libavutil.so`:

```
--enable-asm --enable-cross-compile --enable-gpl --enable-iconv 
--enable-inline-asm --enable-jni --enable-libass --enable-libdav1d 
--enable-libfontconfig --enable-libfreetype --enable-libfribidi 
--enable-libharfbuzz --enable-libjxl --enable-libmp3lame --enable-libopus 
--enable-libsvtav1 --enable-libvpx --enable-libx264 --enable-libx265 
--enable-lto --enable-mediacodec --enable-neon --enable-optimizations 
--enable-pic --enable-pthreads --enable-shared --enable-small 
--enable-swscale --enable-v4l2-m2m --enable-version3 --enable-zlib 
```

Every `.so` reports `LOAD align 0x4000`, so the archive satisfies the 16 KB page-size
requirement. Verify with:

```sh
unzip -o bin/ffmpeg-kit-next-8.1.1.aar 'jni/*' -d /tmp/aarcheck
for f in /tmp/aarcheck/jni/*/*.so; do
  readelf -lW "$f" | awk -v f="$f" '$1=="LOAD"{print f, $NF}'
done | sort -u -k2
```

## Licensing

Built with `--enable-gpl` and `--enable-version3`, so this binary is **GPL-3.0** and the
distributed APK is GPL-3.0 with it. That is deliberate: x264 and x265 are the only route
to CRF and two-pass rate control, which no Android hardware encoder exposes. See
[`../LICENSES/README.md`](../LICENSES/README.md).

GPL-3.0 obliges us to ship corresponding source with the binary. The recipe in
[`../tools/ffmpeg`](../tools/ffmpeg) is that source, and it remains the authority: this
archive is its output, not a substitute for it.

## Rebuilding

```sh
cd tools/ffmpeg
podman build -t ffmpeg-kit-builder:local -f Containerfile .
mkdir -p out
podman run --name ffmpeg-build -v "$PWD/out":/work/out:Z localhost/ffmpeg-kit-builder:local full
cp out/ffmpeg-kit-next-*.aar ../../bin/
```

Update the SHA-256 above when you do. Note that replacing this file adds another ~34 MB
blob to git history permanently, so rebuild only when the FFmpeg version or the configure
flags actually change.

## F-Droid

F-Droid's scanner flags checked-in prebuilt native libraries. If the app is submitted
there, the metadata needs a `scandelete` entry for `bin/` so their build uses the recipe
in `tools/ffmpeg` rather than this archive. Nothing here prevents a from-source build;
the recipe is complete on its own.
