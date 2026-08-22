package org.libremediaconverter.model

/** What happens to the video track. */
sealed interface VideoPlan {
    /** Stream copy — the samples are muxed across untouched. */
    data object Copy : VideoPlan
    data class Encode(val codec: VideoCodec) : VideoPlan
    data object Drop : VideoPlan
}

/** What happens to the audio track. */
sealed interface AudioPlan {
    data object Copy : AudioPlan
    data class Encode(val codec: AudioCodec) : AudioPlan
    data object Drop : AudioPlan
}

/**
 * A resolved conversion: no [VideoCodec.COPY] left to interpret, every track decided.
 */
data class ConversionPlan(val container: Container, val video: VideoPlan, val audio: AudioPlan) {
    /** No track is re-encoded and at least one is copied: a container change and nothing more. */
    val isPureRemux: Boolean
        get() = video !is VideoPlan.Encode &&
            audio !is AudioPlan.Encode &&
            (video is VideoPlan.Copy || audio is AudioPlan.Copy)

    val hasVideo: Boolean get() = video != VideoPlan.Drop
}

/**
 * Turns a requested [OutputSpec] plus what is known about the input into a concrete plan.
 *
 * ## Unknown is not a match
 *
 * When the source codec cannot be identified — [InputProbe.UNPARSEABLE], or FFprobe reported
 * something this app does not recognise — a copy is never attempted. It falls back to re-encoding.
 * This is the same rule [ConcatPlanner] states for the join flow, for the same reason: a needless
 * re-encode costs time, while a wrong stream copy costs the user a file that will not play, and
 * they may not find out until long after the source is gone.
 *
 * ## Why a matching codec is not automatically a copy
 *
 * When the user asks for H.264 and the source is already H.264, copying is usually what they want
 * — but only if the *container* is changing. If both container and codec already match, the only
 * reason to run the job at all is to re-encode it, most likely to make it smaller, and silently
 * copying would hand back a byte-identical file and call it done.
 *
 * That rule assumes re-encoding is the only other reason to convert. If bitrate, resolution or
 * frame-rate controls are ever added, this is the decision that has to be revisited: at that point
 * "same container, same codec" stops implying "compress it".
 */
object CopyPlanner {

    fun plan(spec: OutputSpec, probe: InputProbe): ConversionPlan = ConversionPlan(
        container = spec.container,
        video = planVideo(spec, probe),
        audio = planAudio(spec, probe),
    )

    private fun planVideo(spec: OutputSpec, probe: InputProbe): VideoPlan {
        val requested = spec.videoCodec
        if (requested == VideoCodec.NONE) return VideoPlan.Drop
        if (!probe.hasVideo) return VideoPlan.Drop

        val source = CodecNames.videoFromName(probe.videoCodec)

        if (requested == VideoCodec.COPY) {
            val copyable = source != null &&
                ContainerCapabilities.accepts(spec.container, source, CodecMode.COPY)
            if (copyable) return VideoPlan.Copy
            // Asked to copy but cannot prove it is safe. Re-encode rather than guess; the picker
            // normally refuses this combination before it gets here, so this is the belt to
            // validation's braces — a stale queued job must not turn into a corrupt file.
            return fallbackVideoEncode(spec.container)
        }

        val shouldCopyInstead = source == requested &&
            probe.container != null &&
            probe.container != spec.container &&
            ContainerCapabilities.accepts(spec.container, requested, CodecMode.COPY)

        return if (shouldCopyInstead) VideoPlan.Copy else VideoPlan.Encode(requested)
    }

    private fun planAudio(spec: OutputSpec, probe: InputProbe): AudioPlan {
        val requested = spec.audioCodec
        if (requested == AudioCodec.NONE) return AudioPlan.Drop

        val source = CodecNames.audioFromName(probe.audioCodec)

        if (requested == AudioCodec.COPY) {
            val copyable = source != null &&
                ContainerCapabilities.accepts(spec.container, source, CodecMode.COPY)
            if (copyable) return AudioPlan.Copy
            return fallbackAudioEncode(spec.container)
        }

        val shouldCopyInstead = source == requested &&
            probe.container != null &&
            probe.container != spec.container &&
            ContainerCapabilities.accepts(spec.container, requested, CodecMode.COPY)

        return if (shouldCopyInstead) AudioPlan.Copy else AudioPlan.Encode(requested)
    }

    private fun fallbackVideoEncode(container: Container): VideoPlan =
        ContainerCapabilities.encodableVideo(container).firstOrNull()
            ?.let(VideoPlan::Encode)
            ?: VideoPlan.Drop

    private fun fallbackAudioEncode(container: Container): AudioPlan =
        ContainerCapabilities.encodableAudio(container).firstOrNull()
            ?.let(AudioPlan::Encode)
            ?: AudioPlan.Drop
}
