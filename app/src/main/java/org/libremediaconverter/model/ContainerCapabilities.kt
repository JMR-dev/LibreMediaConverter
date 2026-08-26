package org.libremediaconverter.model

/**
 * Which codecs may go in which container, and whether this app can actually produce them.
 *
 * ## Why two questions, not one
 *
 * "Can MP4 carry AV1?" and "can this app make AV1?" have different answers, and remux is exactly
 * where the difference shows. MP4 carries AV1 and ALAC happily; neither engine here encodes them.
 * Matroska carries Vorbis; nothing in [org.libremediaconverter.ffmpeg.FFmpegCommandBuilder] emits a
 * Vorbis encoder. A single `isValid` boolean would answer one of those questions and give the wrong
 * error for the other — telling a user "MP4 cannot hold AV1" when the truth is "your AV1 file can be
 * copied into MP4, just not re-encoded to it".
 *
 * So the matrix is indexed by mode: [CodecMode.COPY] asks only what the muxer accepts,
 * [CodecMode.ENCODE] additionally asks what this app can encode.
 *
 * This object is the source of truth for container support. `Media3Muxers` reports a narrower set
 * to Transformer — that is the *hardware* subset, and the router decides between them.
 */
object ContainerCapabilities {

    /** Video codecs each container can mux, regardless of whether this app can encode them. */
    private val CARRIES_VIDEO: Map<Container, Set<VideoCodec>> = mapOf(
        Container.MP4 to setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9, VideoCodec.AV1),
        Container.MOV to setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9, VideoCodec.AV1),
        // Matroska is the permissive one: it is a general-purpose container and takes essentially
        // any codec. That is what makes it the natural remux target.
        Container.MKV to setOf(
            VideoCodec.H264,
            VideoCodec.H265,
            VideoCodec.VP8,
            VideoCodec.VP9,
            VideoCodec.AV1,
        ),
        Container.WEBM to setOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1),
        Container.MPEG_TS to setOf(VideoCodec.H264, VideoCodec.H265),
        // AVI predates H.265 and has no standard mapping for it.
        Container.AVI to setOf(VideoCodec.H264),
        Container.FLV to setOf(VideoCodec.H264),
        Container.ASF to setOf(VideoCodec.H264),
        Container.GIF to emptySet(),
        Container.IMAGE_SEQUENCE to emptySet(),
        Container.OGG to emptySet(),
        Container.WAV to emptySet(),
        Container.AAC_ADTS to emptySet(),
        Container.MP3 to emptySet(),
        Container.FLAC to emptySet(),
    )

    private val CARRIES_AUDIO: Map<Container, Set<AudioCodec>> = mapOf(
        Container.MP4 to setOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.OPUS, AudioCodec.FLAC),
        Container.MOV to setOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.PCM),
        Container.MKV to setOf(
            AudioCodec.AAC,
            AudioCodec.OPUS,
            AudioCodec.VORBIS,
            AudioCodec.MP3,
            AudioCodec.FLAC,
            AudioCodec.PCM,
        ),
        Container.WEBM to setOf(AudioCodec.OPUS, AudioCodec.VORBIS),
        Container.MPEG_TS to setOf(AudioCodec.AAC, AudioCodec.MP3),
        Container.AVI to setOf(AudioCodec.MP3, AudioCodec.PCM, AudioCodec.AAC),
        Container.FLV to setOf(AudioCodec.AAC, AudioCodec.MP3),
        Container.ASF to setOf(AudioCodec.AAC, AudioCodec.MP3),
        Container.OGG to setOf(AudioCodec.OPUS, AudioCodec.VORBIS, AudioCodec.FLAC),
        Container.WAV to setOf(AudioCodec.PCM),
        Container.AAC_ADTS to setOf(AudioCodec.AAC),
        Container.MP3 to setOf(AudioCodec.MP3),
        Container.FLAC to setOf(AudioCodec.FLAC),
        Container.GIF to emptySet(),
        Container.IMAGE_SEQUENCE to emptySet(),
    )

    /**
     * Video codecs this app can encode, via either engine.
     *
     * VP8 and AV1 are absent deliberately: `FFmpegCommandBuilder` has no encoder branch for either,
     * and Media3's `setVideoMimeType` rejects both. They remain copyable.
     */
    private val ENCODABLE_VIDEO = setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9)

    /** Vorbis is absent for the same reason: nothing here emits a Vorbis encoder. */
    private val ENCODABLE_AUDIO = setOf(
        AudioCodec.AAC,
        AudioCodec.OPUS,
        AudioCodec.MP3,
        AudioCodec.FLAC,
        AudioCodec.PCM,
    )

    fun accepts(container: Container, codec: VideoCodec, mode: CodecMode): Boolean = when (codec) {
        VideoCodec.NONE -> true
        VideoCodec.COPY -> error("Resolve COPY to a concrete codec before asking the matrix")
        else -> codec in CARRIES_VIDEO.getValue(container) &&
            (mode == CodecMode.COPY || codec in ENCODABLE_VIDEO)
    }

    fun accepts(container: Container, codec: AudioCodec, mode: CodecMode): Boolean = when (codec) {
        AudioCodec.NONE -> true
        AudioCodec.COPY -> error("Resolve COPY to a concrete codec before asking the matrix")
        else -> codec in CARRIES_AUDIO.getValue(container) &&
            (mode == CodecMode.COPY || codec in ENCODABLE_AUDIO)
    }

    /** Every video codec [container] can be asked to produce, for building the picker. */
    fun encodableVideo(container: Container): List<VideoCodec> =
        CARRIES_VIDEO.getValue(container).filter { it in ENCODABLE_VIDEO }

    fun encodableAudio(container: Container): List<AudioCodec> =
        CARRIES_AUDIO.getValue(container).filter { it in ENCODABLE_AUDIO }

    /**
     * Checks a user's choice against the input they picked.
     *
     * Returns alternatives rather than merely refusing, because the Advanced picker deliberately
     * lets an incompatible combination be selected: the error has to explain what would work.
     */
    fun validate(spec: OutputSpec, probe: InputProbe): Validation {
        if (spec.isImageOutput) {
            return if (spec.videoCodec == VideoCodec.NONE && spec.audioCodec == AudioCodec.NONE) {
                Validation.Valid
            } else {
                Validation.Invalid(
                    "${spec.container.label} is an image format and carries no codecs.",
                    listOf(spec.copy(videoCodec = VideoCodec.NONE, audioCodec = AudioCodec.NONE)),
                )
            }
        }

        // Two faces of one rule: the output would carry no tracks at all.
        //
        // The first is visible in the spec alone — NONE on both axes. The second only emerges once
        // the spec meets the probe, because [CopyPlanner] drops a video track the *input* does not
        // have no matter which codec was named for it, so "H.265 + no audio" on an MP3 plans to
        // (Drop, Drop) exactly as "None + None" does. Asking the spec alone answered the first and
        // missed the second, and the miss was not cosmetic: `EditedMediaItem.Builder` refuses that
        // composition with IllegalStateException("Audio and video cannot both be removed"), on
        // Transformer's own thread, where the user would have seen a dead app rather than a reason.
        if (spec.audioCodec == AudioCodec.NONE && (spec.videoCodec == VideoCodec.NONE || !probe.hasVideo)) {
            return Validation.Invalid(
                if (spec.videoCodec == VideoCodec.NONE) {
                    "This would produce an empty file — keep at least one track."
                } else {
                    // Names both halves. "No video track" alone reads as though the video setting
                    // were the only thing wrong, and the user would fix that and still be stuck.
                    "This file has no video track, so turning the audio off too would produce an " +
                        "empty file."
                },
                suggestions(
                    // Ask for both tracks back, then let repair settle what this container and
                    // this input can actually give.
                    spec.copy(videoCodec = VideoCodec.COPY, audioCodec = AudioCodec.COPY),
                    probe,
                    exclude = spec,
                ),
            )
        }

        if (spec.videoCodec != VideoCodec.NONE && !spec.container.canHoldVideo) {
            return Validation.Invalid(
                "${spec.container.label} holds audio only.",
                suggestions(spec, probe),
            )
        }

        validateVideo(spec, probe)?.let { return it }
        validateAudio(spec, probe)?.let { return it }
        return Validation.Valid
    }

    private fun validateVideo(spec: OutputSpec, probe: InputProbe): Validation.Invalid? {
        val codec = spec.videoCodec
        if (codec == VideoCodec.NONE) return null

        if (codec == VideoCodec.COPY) {
            if (!probe.hasVideo) {
                return Validation.Invalid(
                    "This file has no video track to copy.",
                    // Dropping the video is the right shape of answer, but it is only half of one:
                    // `spec.copy(videoCodec = NONE)` is valid exactly when the audio axis already
                    // happened to be fine, and refused otherwise — a Vorbis or PCM source into MP4,
                    // an MP3 into WebM. Handing it to the shared path repairs both axes and drops
                    // anything that still fails, so the chip cannot lead to a second error.
                    suggestions(spec.copy(videoCodec = VideoCodec.NONE), probe, exclude = spec),
                )
            }
            val source = CodecNames.videoFromName(probe.videoCodec)
                ?: return Validation.Invalid(
                    // Never guess. A copy of an unidentified codec is how you ship a file that
                    // does not play — the same reasoning ConcatPlanner records for the join flow.
                    "The source video codec could not be identified, so it cannot be copied.",
                    suggestions(spec, probe),
                )
            if (!accepts(spec.container, source, CodecMode.COPY)) {
                return Validation.Invalid(
                    "${spec.container.label} cannot hold ${source.label} video.",
                    suggestions(spec, probe),
                )
            }
            return null
        }

        if (codec !in CARRIES_VIDEO.getValue(spec.container)) {
            return Validation.Invalid(
                "${spec.container.label} cannot hold ${codec.label} video.",
                suggestions(spec, probe),
            )
        }
        if (codec !in ENCODABLE_VIDEO) {
            return Validation.Invalid(
                "This app cannot encode ${codec.label}. It can still be copied from a " +
                    "${codec.label} source.",
                suggestions(spec, probe),
            )
        }
        return null
    }

    private fun validateAudio(spec: OutputSpec, probe: InputProbe): Validation.Invalid? {
        val codec = spec.audioCodec
        if (codec == AudioCodec.NONE) return null

        if (codec == AudioCodec.COPY) {
            val source = CodecNames.audioFromName(probe.audioCodec)
                ?: return Validation.Invalid(
                    "The source audio codec could not be identified, so it cannot be copied.",
                    suggestions(spec, probe),
                )
            if (!accepts(spec.container, source, CodecMode.COPY)) {
                return Validation.Invalid(
                    "${spec.container.label} cannot hold ${source.label} audio.",
                    suggestions(spec, probe),
                )
            }
            return null
        }

        if (codec !in CARRIES_AUDIO.getValue(spec.container)) {
            return Validation.Invalid(
                "${spec.container.label} cannot hold ${codec.label} audio.",
                suggestions(spec, probe),
            )
        }
        if (codec !in ENCODABLE_AUDIO) {
            return Validation.Invalid(
                "This app cannot encode ${codec.label} audio. It can still be copied from a " +
                    "${codec.label} source.",
                suggestions(spec, probe),
            )
        }
        return null
    }

    /**
     * Combinations that would work, closest to what was asked for.
     *
     * Every entry is repaired on *both* codec axes, not just the one that failed. Fixing only the
     * offending axis is the obvious implementation and it is wrong: "H.264 in WebM" swaps the video
     * to VP9 and leaves AAC behind, which WebM cannot hold either, so the suggestion is as invalid
     * as the thing it was meant to fix. `ContainerCapabilitiesTest` asserts every suggestion
     * validates, which is what caught that.
     */
    private fun suggestions(
        spec: OutputSpec,
        probe: InputProbe,
        /** What not to suggest. Differs from [spec] when the caller repaired it first. */
        exclude: OutputSpec = spec,
    ): List<OutputSpec> {
        val containers = buildList {
            add(spec.container)
            // A container that can hold what the user actually asked for keeps their intent.
            firstContainerHolding(spec.videoCodec, probe)?.let(::add)
            CodecNames.videoFromName(probe.videoCodec)
                ?.let { firstContainerHolding(it, probe) }
                ?.let(::add)
        }

        return containers.distinct()
            .mapNotNull { repair(spec.copy(container = it), probe) }
            .filter { it != exclude }
            .distinct()
            .filter { validate(it, probe).isValid }
            .take(MAX_SUGGESTIONS)
    }

    /**
     * How many alternatives an [Validation.Invalid] offers. Enough to show a real choice,
     * few enough that the error stays readable.
     */
    private const val MAX_SUGGESTIONS = 3

    /** Best valid spec for this container, preserving as much of the request as possible. */
    private fun repair(spec: OutputSpec, probe: InputProbe): OutputSpec? {
        val container = spec.container
        if (container == Container.GIF || container == Container.IMAGE_SEQUENCE) return null

        val video = repairVideo(spec, probe)
        val audio = repairAudio(spec, probe)
        if (video == VideoCodec.NONE && audio == AudioCodec.NONE) return null
        return spec.copy(videoCodec = video, audioCodec = audio)
    }

    private fun repairVideo(spec: OutputSpec, probe: InputProbe): VideoCodec {
        val container = spec.container
        if (spec.videoCodec == VideoCodec.NONE || !container.canHoldVideo) return VideoCodec.NONE
        // There is no video track to make one out of, so naming a codec would be a suggestion
        // [CopyPlanner] drops on the floor. It also read as a non-sequitur: before this line, the
        // repair offered for an MP3 was "H.264", the first codec MP4 happens to encode.
        if (!probe.hasVideo) return VideoCodec.NONE

        val source = CodecNames.videoFromName(probe.videoCodec)
        val copyable = source != null && accepts(container, source, CodecMode.COPY)

        return when {
            // An explicit copy that works is exactly what was asked for.
            spec.videoCodec == VideoCodec.COPY && copyable -> VideoCodec.COPY
            // The requested codec is what the source already is, and we cannot encode it — but we
            // can carry it across untouched. That is the useful answer for AV1 and VP8.
            copyable && source == spec.videoCodec -> VideoCodec.COPY
            spec.videoCodec != VideoCodec.COPY &&
                accepts(container, spec.videoCodec, CodecMode.ENCODE) -> spec.videoCodec
            else -> encodableVideo(container).firstOrNull() ?: VideoCodec.NONE
        }
    }

    private fun repairAudio(spec: OutputSpec, probe: InputProbe): AudioCodec {
        val container = spec.container
        if (spec.audioCodec == AudioCodec.NONE) return AudioCodec.NONE

        val source = CodecNames.audioFromName(probe.audioCodec)
        val copyable = source != null && accepts(container, source, CodecMode.COPY)

        return when {
            spec.audioCodec == AudioCodec.COPY && copyable -> AudioCodec.COPY
            copyable && source == spec.audioCodec -> AudioCodec.COPY
            spec.audioCodec != AudioCodec.COPY &&
                accepts(container, spec.audioCodec, CodecMode.ENCODE) -> spec.audioCodec
            else -> encodableAudio(container).firstOrNull() ?: AudioCodec.NONE
        }
    }

    /** A container that can hold [codec], preferring the one the input already uses. */
    private fun firstContainerHolding(codec: VideoCodec, probe: InputProbe): Container? {
        if (codec == VideoCodec.NONE || codec == VideoCodec.COPY) return null
        val holders = Container.entries.filter { codec in CARRIES_VIDEO.getValue(it) }
        return holders.firstOrNull { it == probe.container } ?: holders.firstOrNull()
    }
}

/** Whether a track is being copied through or re-encoded. */
enum class CodecMode { COPY, ENCODE }

sealed interface Validation {
    data object Valid : Validation

    /**
     * @param suggestions combinations that would work. Never empty in practice, and the Advanced
     *   picker renders them as one-tap fixes; every entry is itself valid, which
     *   `ContainerCapabilitiesTest` asserts.
     */
    data class Invalid(val message: String, val suggestions: List<OutputSpec>) : Validation

    val isValid: Boolean get() = this is Valid
}
