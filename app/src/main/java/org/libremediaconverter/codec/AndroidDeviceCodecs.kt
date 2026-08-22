package org.libremediaconverter.codec

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.VideoCodec

/**
 * What this device's codec hardware can actually do.
 *
 * Enumerated once and cached: MediaCodecList is not cheap, and the answer cannot
 * change while the process is alive.
 *
 * Two details that are easy to get wrong:
 *
 * - Devices expose **aliases** for the same underlying codec, so the list must be
 *   deduplicated by canonical name or capabilities get counted several times.
 * - `isHardwareAccelerated()` is declared by the vendor and, in the platform's own
 *   words, "cannot be tested for correctness". It is a hint, not a guarantee, which is
 *   why the router treats a failed hardware export as a signal to fall back rather
 *   than trusting this up front.
 */
class AndroidDeviceCodecs private constructor(
    private val hardwareEncodeMimes: Set<String>,
    private val decodeMimes: Set<String>,
) : DeviceCodecs {

    override fun canEncode(codec: VideoCodec): Boolean = mimeFor(codec)?.let { it in hardwareEncodeMimes } ?: true

    override fun canDecode(codecName: String): Boolean {
        // The platform already failed to parse this input, so there is nothing to
        // decode with. Send it to FFmpeg rather than letting Media3 fail later.
        if (codecName == InputProbe.UNPARSEABLE) return false
        return mimeForCodecName(codecName)?.let { it in decodeMimes } ?: true
    }

    fun hardwareEncoders(): Set<String> = hardwareEncodeMimes

    companion object {
        private const val TAG = "AndroidDeviceCodecs"

        @Volatile
        private var cached: AndroidDeviceCodecs? = null

        fun get(): AndroidDeviceCodecs = cached ?: synchronized(this) { cached ?: probe().also { cached = it } }

        private fun probe(): AndroidDeviceCodecs {
            val encoders = mutableSetOf<String>()
            val decoders = mutableSetOf<String>()
            val seen = mutableSetOf<String>()

            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                    // Aliases point at the same underlying codec; counting both would
                    // double-count capabilities.
                    if (info.isAlias) return@forEach
                    if (!seen.add(info.canonicalName)) return@forEach

                    info.supportedTypes.forEach { mime ->
                        if (!mime.startsWith("video/")) return@forEach
                        if (info.isEncoder) {
                            if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                                encoders += mime
                            }
                        } else {
                            decoders += mime
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Codec enumeration failed; assuming permissive.", it) }

            Log.i(TAG, "Hardware video encoders: $encoders")
            return AndroidDeviceCodecs(encoders, decoders)
        }

        private fun mimeFor(codec: VideoCodec): String? = when (codec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            VideoCodec.VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
            VideoCodec.VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
            VideoCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
            // Nothing is encoded for either, so there is no encoder to look for. Returning null
            // makes canEncode answer true, which is the right answer: a copied or absent track
            // places no demand on the hardware.
            VideoCodec.COPY, VideoCodec.NONE -> null
        }

        /** Maps an FFprobe-style codec name onto a MediaFormat MIME type. */
        private fun mimeForCodecName(name: String): String? = when (name.lowercase()) {
            "h264", "avc", "avc1" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "hevc", "h265", "hvc1" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "vp8" -> MediaFormat.MIMETYPE_VIDEO_VP8
            "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "av1", "av01" -> MediaFormat.MIMETYPE_VIDEO_AV1
            "mpeg4" -> MediaFormat.MIMETYPE_VIDEO_MPEG4
            // Unknown to us: assume the platform can handle it and let a failed export
            // trigger the FFmpeg fallback, rather than pre-emptively refusing hardware.
            else -> null
        }

        /** Test seam: lets instrumented tests build a probe from explicit sets. */
        fun forTesting(encoders: Set<String>, decoders: Set<String>) = AndroidDeviceCodecs(encoders, decoders)
    }
}
