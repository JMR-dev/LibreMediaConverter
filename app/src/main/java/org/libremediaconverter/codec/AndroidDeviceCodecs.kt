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
 * - **An enumeration that fails answers no to everything**, which sends every job to
 *   FFmpeg. Empty sets are not a permissive default: `canEncode` looks a MIME type up in
 *   [hardwareEncodeMimes] and finds nothing there. That is the intended answer — FFmpeg
 *   can do whatever Media3 can, only slower — but it is the opposite of what this class
 *   said until #194, so it is written down rather than left to be re-derived.
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

        /**
         * One entry of the platform's codec list, reduced to what the rules below read.
         *
         * The five booleans and the type list are the whole of what [capabilitiesFrom] needs, and
         * none of them can be set on a `MediaCodecInfo` from a test: Robolectric ships
         * `MediaCodecInfoBuilder`, but it has no `setIsAlias` and no `setCanonicalName`, which is
         * exactly the objection #133 raised against reaching this code through
         * `ShadowMediaCodecList`. That objection is about the shadow. It does not apply to a
         * function that takes its own entry type, which is why this exists.
         */
        internal data class CodecEntry(
            val canonicalName: String,
            val isAlias: Boolean,
            val isEncoder: Boolean,
            val isHardwareAccelerated: Boolean,
            val isSoftwareOnly: Boolean,
            val supportedTypes: List<String>,
        )

        /**
         * The enumeration rules, over entries a caller chooses.
         *
         * [probe] is the only production caller and supplies the real codec list; a test supplies
         * its own, which is the point — the two rules this class's KDoc calls out as easy to get
         * wrong, the alias skip and the canonical-name dedup, are unreachable any other way.
         *
         * **`enumerate` returns a `Sequence`, deliberately.** The `runCatching` has to wrap the
         * *iteration* rather than a list built before it, because a `MediaCodecInfo` whose
         * properties throw does so partway through — and when that happens the codecs already read
         * are kept. Taking a `List` here would move that throw outside the loop and silently turn a
         * partial answer into an empty one. That behaviour predates this seam; a `List` parameter
         * would have changed it as a side effect of a refactor.
         *
         * **An enumeration that fails answers restrictively, and that is deliberate.** The sets
         * come back empty, and `"video/avc" in emptySet()` is `false`, so [canEncode] and
         * [canDecode] both answer no and every job routes to FFmpeg. FFmpeg can do everything
         * Media3 can, only slower, so refusing the hardware path is the safe reading of "we could
         * not find out what this device supports". This used to log "assuming permissive", which
         * described the opposite of what the code does.
         */
        internal fun capabilitiesFrom(enumerate: () -> Sequence<CodecEntry>): AndroidDeviceCodecs {
            val encoders = mutableSetOf<String>()
            val decoders = mutableSetOf<String>()
            val seen = mutableSetOf<String>()

            runCatching {
                enumerate().forEach { entry ->
                    // Aliases point at the same underlying codec; counting both would
                    // double-count capabilities.
                    if (entry.isAlias) return@forEach
                    if (!seen.add(entry.canonicalName)) return@forEach

                    entry.supportedTypes.forEach { mime ->
                        if (!mime.startsWith("video/")) return@forEach
                        if (entry.isEncoder) {
                            if (entry.isHardwareAccelerated && !entry.isSoftwareOnly) {
                                encoders += mime
                            }
                        } else {
                            decoders += mime
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Codec enumeration failed; routing everything to FFmpeg.", it) }

            Log.i(TAG, "Hardware video encoders: $encoders")
            return AndroidDeviceCodecs(encoders, decoders)
        }

        /**
         * The thin edge: the real codec list, mapped onto [CodecEntry] one at a time.
         *
         * Lazily, so a property that throws does it inside [capabilitiesFrom]'s `runCatching` and
         * on the entry that caused it — see that function's note on why the parameter is a
         * `Sequence`.
         */
        private fun probe(): AndroidDeviceCodecs = capabilitiesFrom {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence().map { info ->
                CodecEntry(
                    canonicalName = info.canonicalName,
                    isAlias = info.isAlias,
                    isEncoder = info.isEncoder,
                    isHardwareAccelerated = info.isHardwareAccelerated,
                    isSoftwareOnly = info.isSoftwareOnly,
                    supportedTypes = info.supportedTypes.toList(),
                )
            }
        }

        /**
         * `internal` rather than `private` so the cross-check test can ask what a [VideoCodec]
         * means here and compare it with what [NAME_TO_MIME] says the same codec's names mean.
         * The JVM test source set is a friend of `main`, so this stays invisible outside the
         * module — the precedent is `MainActivity`'s `Destination`.
         */
        internal fun mimeFor(codec: VideoCodec): String? = when (codec) {
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

        /**
         * FFprobe-style codec names, and the MediaFormat MIME type each one asks about.
         *
         * This is the same vocabulary `CodecNames.VIDEO_ALIASES` holds, written out a second time
         * because this side has to answer in platform MIME types and `model` does not depend on
         * Android. Two copies of one vocabulary drift, and these had: `x264`, `hev1`, `x265` and
         * `vp09` resolved for display and routing and fell through to null here, so the app ran
         * the capability check blind on inputs it had already identified (#87). They are listed
         * now, which **changes behaviour** for those four names — see [mimeForCodecName].
         *
         * A map rather than a `when` because a `when` cannot be enumerated, and `CodecVocabularyTest`
         * has to walk both key sets to notice the next divergence.
         */
        internal val NAME_TO_MIME: Map<String, String> = mapOf(
            "h264" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "avc" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "avc1" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "x264" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "h265" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "hvc1" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "hev1" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "x265" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "vp8" to MediaFormat.MIMETYPE_VIDEO_VP8,
            "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9,
            "vp09" to MediaFormat.MIMETYPE_VIDEO_VP9,
            "av1" to MediaFormat.MIMETYPE_VIDEO_AV1,
            "av01" to MediaFormat.MIMETYPE_VIDEO_AV1,
            "mpeg4" to MediaFormat.MIMETYPE_VIDEO_MPEG4,
        )

        /**
         * The names in [NAME_TO_MIME] that no [VideoCodec] member spells, and why.
         *
         * MPEG-4 Part 2 is decodable input the app never targets, so there is no enum for it and
         * `CodecNames` is right not to carry it. That makes it the one place the two tables
         * legitimately differ. It is listed rather than implied so the cross-check can tell a
         * documented asymmetry from a fresh drift — and so the list itself is checked: a name here
         * that `CodecNames` does resolve is a divergence being waved through, and the test fails on
         * it.
         */
        internal val DECODE_ONLY_NAMES: Set<String> = setOf("mpeg4")

        /**
         * Maps an FFprobe-style codec name onto a MediaFormat MIME type.
         *
         * Null keeps its documented meaning — unknown to us: assume the platform can handle it and
         * let a failed export trigger the FFmpeg fallback, rather than pre-emptively refusing
         * hardware. What changed with #87 is which names are unknown. Four that FFmpeg genuinely
         * emits used to land here and be treated as unknown while the rest of the app knew exactly
         * what they were; a device without the matching decoder now routes them to FFmpeg up front
         * instead of spending a doomed hardware attempt to find out.
         */
        internal fun mimeForCodecName(name: String): String? = NAME_TO_MIME[name.lowercase()]

        /** Test seam: lets a test build a probe from explicit sets, on a device or on the JVM. */
        fun forTesting(encoders: Set<String>, decoders: Set<String>) = AndroidDeviceCodecs(encoders, decoders)
    }
}
