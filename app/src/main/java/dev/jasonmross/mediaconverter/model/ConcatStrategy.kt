package dev.jasonmross.mediaconverter.model

/**
 * How to join several inputs into one file.
 *
 * The naive answer — FFmpeg's `concat` demuxer — only works when every input shares a
 * codec, resolution and timebase. That is true for clips from one camera in one
 * session, and false for the case users actually hit: two clips from different phones,
 * or a screen recording joined to a camera clip. The demuxer does not fail loudly on
 * mismatch; it can produce a file whose second half is garbled.
 *
 * So the strategy is chosen from the inputs rather than assumed.
 */
enum class ConcatStrategy {
    /**
     * Stream copy via the `concat` demuxer. No re-encode, near-instant, lossless.
     * Requires every input to agree on codec, resolution, frame rate and timebase.
     */
    STREAM_COPY,

    /**
     * Re-encode through the `concat` filter, normalising to a common format.
     * Slower and lossy, but it is the only correct answer for mismatched inputs.
     */
    REENCODE,
}

/** The properties that decide whether inputs can be joined without re-encoding. */
data class ConcatInput(
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val frameRate: Int,
)

object ConcatPlanner {

    /**
     * Picks a strategy for [inputs].
     *
     * Errs towards [ConcatStrategy.REENCODE]: a needless re-encode costs time, while a
     * wrong stream copy costs the user a corrupt file they may not notice until later.
     */
    fun plan(inputs: List<ConcatInput>): ConcatStrategy {
        if (inputs.size < 2) return ConcatStrategy.STREAM_COPY

        val first = inputs.first()
        // A null codec means we could not determine it. That is not evidence of a
        // match, so it must not be treated as one.
        if (inputs.any { it.videoCodec == null || it.videoCodec != first.videoCodec }) {
            return ConcatStrategy.REENCODE
        }
        if (inputs.any { it.audioCodec != first.audioCodec }) {
            return ConcatStrategy.REENCODE
        }
        if (inputs.any { it.width != first.width || it.height != first.height }) {
            return ConcatStrategy.REENCODE
        }
        if (inputs.any { it.frameRate != first.frameRate }) {
            return ConcatStrategy.REENCODE
        }
        return ConcatStrategy.STREAM_COPY
    }
}
