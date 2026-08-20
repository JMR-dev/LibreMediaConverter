package dev.jasonmross.mediaconverter.ffmpeg

import dev.jasonmross.mediaconverter.model.ConversionRequest
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier

/**
 * Builds FFmpeg argument lists.
 *
 * Kept free of Android types so the whole matrix can be unit tested on the JVM. A
 * wrong flag here produces a corrupt file or a silent quality regression, which is
 * exactly the kind of thing that should not need a device to catch.
 *
 * Arguments are produced as a list rather than a shell string: paths routinely contain
 * spaces, and a list has no quoting rules to get wrong.
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

    fun build(
        request: ConversionRequest,
        inputPath: String,
        outputPath: String,
    ): List<String> = buildList {
        add("-hide_banner")
        // Overwrite: the output path is one we just created in our own cache.
        add("-y")
        add("-i"); add(inputPath)

        addAll(streamSelection(request.format))
        addAll(videoArgs(request))
        addAll(audioArgs(request.format))
        addAll(containerArgs(request.format))

        add(outputPath)
    }

    private fun streamSelection(format: OutputFormat): List<String> = when {
        // -vn drops video entirely. Without it FFmpeg will happily try to carry a video
        // stream into an audio container and fail at the muxer.
        format.isAudioOnly -> listOf("-vn")
        format == OutputFormat.GIF || format == OutputFormat.FRAMES_PNG -> listOf("-an")
        else -> emptyList()
    }

    private fun videoArgs(request: ConversionRequest): List<String> {
        val format = request.format
        if (format.isAudioOnly) return emptyList()

        return when (format) {
            OutputFormat.GIF -> listOf(
                // One pass with a generated palette. GIF is limited to 256 colours, and
                // the default palette produces visibly banded output; split+palettegen
                // and paletteuse in a single graph avoids a temporary palette file.
                "-vf",
                "fps=12,scale=480:-1:flags=lanczos,split[a][b];" +
                    "[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer",
                "-loop", "0",
            )

            OutputFormat.FRAMES_PNG -> listOf("-vf", "fps=1", "-vsync", "0")

            else -> when (request.quality) {
                // Software encoding: this is what the GPL licence buys. CRF targets a
                // quality level and lets the bitrate fall where it may, which is what
                // "compress this well" actually needs. No hardware encoder on Android
                // exposes it.
                QualityTier.BEST -> when (format.videoCodec) {
                    dev.jasonmross.mediaconverter.model.VideoCodec.H265 -> listOf(
                        "-c:v", "libx265", "-crf", "$CRF_H265", "-preset", "medium",
                        // Without this, many players and Apple devices refuse HEVC in MP4.
                        "-tag:v", "hvc1",
                    )
                    dev.jasonmross.mediaconverter.model.VideoCodec.VP9 -> listOf(
                        "-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0",
                    )
                    else -> listOf(
                        "-c:v", "libx264", "-crf", "$CRF_H264", "-preset", "medium",
                        // 4:2:0 for compatibility; some sources decode to 4:4:4, which
                        // most hardware players cannot handle.
                        "-pix_fmt", "yuv420p",
                    )
                }

                // FFmpeg's MediaCodec wrappers, used when a job landed on FFmpeg for
                // container reasons but the user still asked for speed.
                //
                // Only when the device actually has a hardware encoder. Otherwise the
                // wrapper binds to the platform's software codec (c2.android.*) and
                // encodes far slower than libx264/libx265 would, while still calling
                // itself the fast path. In that case use a real software encoder with a
                // fast preset, which is what the user asked for in substance.
                QualityTier.FAST -> when (format.videoCodec) {
                    dev.jasonmross.mediaconverter.model.VideoCodec.H265 ->
                        if (request.hardwareEncodeAvailable) {
                            listOf("-c:v", "hevc_mediacodec", "-b:v", "5M", "-tag:v", "hvc1")
                        } else {
                            listOf("-c:v", "libx265", "-crf", "$CRF_H265", "-preset", "veryfast", "-tag:v", "hvc1")
                        }
                    dev.jasonmross.mediaconverter.model.VideoCodec.VP9 ->
                        listOf("-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0", "-deadline", "realtime")
                    else ->
                        if (request.hardwareEncodeAvailable) {
                            listOf("-c:v", "h264_mediacodec", "-b:v", "5M")
                        } else {
                            listOf("-c:v", "libx264", "-crf", "$CRF_H264", "-preset", "veryfast", "-pix_fmt", "yuv420p")
                        }
                }
            }
        }
    }

    private fun audioArgs(format: OutputFormat): List<String> = when (format) {
        OutputFormat.MP3 -> listOf("-c:a", "libmp3lame", "-q:a", "2")
        OutputFormat.FLAC -> listOf("-c:a", "flac")
        OutputFormat.WAV -> listOf("-c:a", "pcm_s16le")
        OutputFormat.OPUS -> listOf("-c:a", "libopus", "-b:a", "128k")
        OutputFormat.M4A_AAC -> listOf("-c:a", "aac", "-b:a", "192k")
        OutputFormat.GIF, OutputFormat.FRAMES_PNG -> emptyList()
        OutputFormat.WEBM_VP9 -> listOf("-c:a", "libopus", "-b:a", "128k")
        else -> listOf("-c:a", "aac", "-b:a", "192k")
    }

    private fun containerArgs(format: OutputFormat): List<String> = when (format.container) {
        // Move the moov atom to the front so the file starts playing before it is
        // fully downloaded. This is also the reason output never goes through a SAF
        // file descriptor: faststart has to seek backwards to rewrite the header.
        dev.jasonmross.mediaconverter.model.Container.MP4 -> listOf("-movflags", "+faststart")
        else -> emptyList()
    }

    /** Output filename pattern for formats that emit many files. */
    fun outputPattern(format: OutputFormat, baseName: String): String =
        if (format == OutputFormat.FRAMES_PNG) "${baseName}_%04d.png" else baseName
}
