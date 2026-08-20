# Local FFmpeg AAR

`ffmpeg-kit-next-*.aar` is **built, not committed**. Build it with:

```sh
cd tools/ffmpeg
podman build -t ffmpeg-kit-builder:local -f Containerfile .
mkdir -p out
podman run --name ffmpeg-build -v "$PWD/out":/work/out:Z \
    localhost/ffmpeg-kit-builder:local full
cp out/ffmpeg-kit-next-*.aar ../../app/libs/
```

The `.aar` is deliberately gitignored. It is a ~35 MB binary blob, and F-Droid's build
process strips checked-in prebuilt native libraries — committing it would break the
F-Droid build and bloat the repository. The reproducible recipe in `tools/ffmpeg/` is
the artifact of record, and it also serves as the GPL corresponding-source obligation.
