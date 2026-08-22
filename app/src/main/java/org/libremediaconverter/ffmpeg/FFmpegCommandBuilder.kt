package org.libremediaconverter.ffmpeg

import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.CodecNames
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionPlan
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan

/**
 * Builds FFmpeg argument lists.
 *
 * Kept free of Android types so the whole matrix can be unit tested on the JVM. A
 * wrong flag here produces a corrupt file or a silent quality regression, which is
 * exactly the kind of thing that should not need a device to catch.
 *
 * Arguments are produced as a list rather than a shell string: paths routinely contain
 * spaces, and a list has no quoting rules to get wrong.
 *
 * Every decision comes from [CopyPlanner], so "copy this track" and "re-encode that one" are
 * settled before any flag is chosen, and the same plan drives the routing decision.
 */
object FFmpegCommandBuilder {

    /**
     * CRF values, chosen per codec rather than shared.
     *
     * x265 is roughly one CRF step "stronger" than x264 at the same number, so a
     * shared constant would silently make HEVC output larger than intended.
     */
    private const val CRF_H264 = 20
    private const val CRF_H265 = 24

    /**
     * Force 4:2:0 chroma on every video encode.
     *
     * Sources are not always 4:2:0. Real footage encoded as H.264 High 4:4:4 Predictive
     * exists, and FFmpeg will happily decode it to yuv444p and then hand those frames to
     * an encoder that cannot take them. The MediaCodec wrappers fail hard in that case —
     * "Invalid to call at Released state" partway through the export — and hardware
     * players reject 4:4:4 output anyway. Naming the pixel format makes FFmpeg insert
     * the conversion instead of failing.
     *
     * Deliberately absent from the copy path: a stream copy never decodes frames, so there is
     * no pixel format to convert and the flag would be meaningless.
     */
    private val PIX_FMT = listOf("-pix_fmt", "yuv420p")

    /** Containers in the ISO base-media family, where HEVC needs the hvc1 brand. */
    private val MP4_FAMILY = setOf(Container.MP4, Container.MOV)

    fun build(request: ConversionRequest, inputPath: String, outputPath: String): List<String> {
        val plan = CopyPlanner.plan(request.spec, request.probe)
        return buildList {
            add("-hide_banner")
            // Overwrite: the output path is one we just created in our own cache.
            add("-y")
            add("-i")
            add(inputPath)

            if (request.spec.isImageOutput) {
                addAll(imageArgs(request))
            } else {
                addAll(videoArgs(plan, request))
                addAll(audioArgs(plan))
            }
            addAll(containerArgs(plan))

            add(outputPath)
        }
    }

    private fun imageArgs(request: ConversionRequest): List<String> = when (request.container) {
        Container.GIF -> listOf(
            "-an",
            // One pass with a generated palette. GIF is limited to 256 colours, and
            // the default palette produces visibly banded output; split+palettegen
            // and paletteuse in a single graph avoids a temporary palette file.
            "-vf",
            "fps=12,scale=480:-1:flags=lanczos,split[a][b];" +
                "[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer",
            "-loop",
            "0",
        )

        else -> listOf("-an", "-vf", "fps=1", "-vsync", "0")
    }

    private fun videoArgs(plan: ConversionPlan, request: ConversionRequest): List<String> =
        when (val video = plan.video) {
            // -vn drops video entirely. Without it FFmpeg will happily try to carry a video
            // stream into an audio container and fail at the muxer.
            VideoPlan.Drop -> listOf("-vn")

            VideoPlan.Copy -> buildList {
                add("-c:v")
                add("copy")
                // The hvc1 brand matters on the copy path too, not just when encoding: remuxing
                // HEVC out of Matroska into MP4 otherwise produces a file Apple devices and many
                // hardware players refuse, even though the samples are byte-identical.
                addAll(hevcTagIfNeeded(plan, request))
            }

            is VideoPlan.Encode -> encodeVideo(video.codec, request.quality)
        }

    private fun encodeVideo(codec: VideoCodec, quality: QualityTier): List<String> {
        val preset = if (quality == QualityTier.BEST) "medium" else "veryfast"
        return when (codec) {
            // Software encoding: this is what the GPL licence buys. CRF targets a
            // quality level and lets the bitrate fall where it may, which is what
            // "compress this well" actually needs. No hardware encoder on Android
            // exposes it.
            //
            // Software encoding, always. FFmpeg's *_mediacodec encoders used to be selected
            // here, on the theory that a job routed to FFmpeg for container reasons could still
            // encode in hardware. In practice they are undocumented, per-device flaky, and were
            // observed failing twice on real footage on a Pixel 10 Pro XL -- once binding to a
            // software codec while claiming to be the fast path, and once dying mid-export with
            // "Error submitting video frame to the encoder" even after the pixel format was
            // pinned. They also duplicate, badly, something Media3 already does properly. A job
            // only reaches FFmpeg because Media3 could not handle it, which is itself evidence
            // that hardware encoding is unlikely to work for that input. Fast therefore means a
            // fast *preset*, not a different encoder.
            VideoCodec.H265 -> listOf(
                "-c:v",
                "libx265",
                "-crf",
                "$CRF_H265",
                "-preset",
                preset,
                // Without this, many players and Apple devices refuse HEVC in MP4.
                "-tag:v",
                "hvc1",
            ) + PIX_FMT

            VideoCodec.VP9 -> buildList {
                addAll(listOf("-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0"))
                if (quality == QualityTier.FAST) addAll(listOf("-deadline", "realtime"))
                addAll(PIX_FMT)
            }

            VideoCodec.H264 -> listOf(
                "-c:v",
                "libx264",
                "-crf",
                "$CRF_H264",
                "-preset",
                preset,
            ) + PIX_FMT

            // No silent substitution. A trailing `else -> libx264` would hand back H.264 for a
            // VP8 or AV1 request without a word — structurally the same defect as the old
            // `media3MimeType()`, whose `else -> VIDEO_H265` is what put an HEVC video track in a
            // file named `.m4a`. ContainerCapabilities refuses these combinations and
            // ConversionWorker checks before enqueuing, so reaching here is a bug worth hearing
            // about rather than papering over.
            VideoCodec.VP8, VideoCodec.AV1 -> error(
                "This app cannot encode ${codec.label}; it can only copy an existing " +
                    "${codec.label} stream.",
            )

            VideoCodec.COPY, VideoCodec.NONE -> error(
                "encodeVideo called for $codec, which is not an encode",
            )
        }
    }

    private fun hevcTagIfNeeded(plan: ConversionPlan, request: ConversionRequest): List<String> {
        if (plan.container !in MP4_FAMILY) return emptyList()
        val sourceIsHevc = request.probe.videoCodec
            ?.let(CodecNames::videoFromName) == VideoCodec.H265
        return if (sourceIsHevc) listOf("-tag:v", "hvc1") else emptyList()
    }

    private fun audioArgs(plan: ConversionPlan): List<String> = when (val audio = plan.audio) {
        AudioPlan.Drop -> listOf("-an")
        AudioPlan.Copy -> listOf("-c:a", "copy")
        is AudioPlan.Encode -> when (audio.codec) {
            AudioCodec.MP3 -> listOf("-c:a", "libmp3lame", "-q:a", "2")
            AudioCodec.FLAC -> listOf("-c:a", "flac")
            AudioCodec.PCM -> listOf("-c:a", "pcm_s16le")
            AudioCodec.OPUS -> listOf("-c:a", "libopus", "-b:a", "128k")
            AudioCodec.VORBIS -> listOf("-c:a", "libvorbis", "-q:a", "5")
            else -> listOf("-c:a", "aac", "-b:a", "192k")
        }
    }

    private fun containerArgs(plan: ConversionPlan): List<String> = buildList {
        // Name the muxer rather than letting FFmpeg infer it from the output path. Inference is
        // unreliable for MPEG-TS and ASF, and the app now lets the user pick a container
        // independently of the preset that used to imply it.
        add("-f")
        add(plan.container.ffmpegFormat)

        if (plan.container in MP4_FAMILY) {
            // Move the moov atom to the front so the file starts playing before it is
            // fully downloaded. This is also the reason output never goes through a SAF
            // file descriptor: faststart has to seek backwards to rewrite the header.
            add("-movflags")
            add("+faststart")
        }
    }

    /** Output filename pattern for formats that emit many files. */
    fun outputPattern(container: Container, baseName: String): String =
        if (container == Container.IMAGE_SEQUENCE) "${baseName}_%04d.png" else baseName
}
