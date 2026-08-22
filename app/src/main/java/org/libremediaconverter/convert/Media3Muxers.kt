package org.libremediaconverter.convert

import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer
import org.libremediaconverter.model.Container

/**
 * Which containers Media3 can actually write, and why it is only one.
 *
 * ## The four other muxers cannot be driven by Transformer
 *
 * `media3-muxer` 1.11.0 ships `WebmMuxer`, `OggMuxer`, `WavMuxer` and `AacMuxer` alongside the MP4
 * ones, which reads like four more containers on the hardware path. It is not: **all four throw
 * `UnsupportedOperationException` from `addMetadataEntry`**, and
 * `MuxerWrapper.addTrackFormat` calls it for every metadata entry on the track format. Any real
 * recording carries some — a creation timestamp is enough — so the export dies partway through:
 *
 * ```
 * Caused by: java.lang.UnsupportedOperationException
 *     at androidx.media3.muxer.OggMuxer.addMetadataEntry(OggMuxer.java:123)
 *     at androidx.media3.transformer.MuxerWrapper.addTrackFormat(MuxerWrapper.java:488)
 * ```
 *
 * They are standalone muxers, not Transformer-compatible ones. WAV fails a second way even before
 * that: `DefaultEncoderFactory` has no PCM encoder, so Transformer reports "No MIME type is
 * supported by both encoder and muxer" rather than passing raw samples through.
 *
 * Both were observed on a CI API 35 emulator, not inferred — the tests that found them were written
 * on the assumption these containers worked.
 *
 * So Media3 writes MP4 and nothing else. WebM, Ogg, WAV and raw AAC belong to FFmpeg, which already
 * produces all of them and has instrumented coverage asserting the produced files.
 */
@UnstableApi
object Media3Muxers {

    /**
     * The factory for [container], or null when Media3 cannot write it.
     *
     * A null here must agree with [org.libremediaconverter.model.ConversionRouter]'s container set;
     * `Media3MuxersTest` asserts they do. They drifted once already, and expensively: the router
     * claimed five containers while the engine silently wrote MP4 for all of them.
     */
    fun factoryFor(container: Container): Muxer.Factory? = when (container) {
        // Transformer's own default. Named explicitly so the engine states its container rather
        // than inheriting one, which is how the MP4-for-everything bug went unnoticed.
        Container.MP4 -> DefaultMuxer.Factory()

        Container.WEBM,
        Container.OGG,
        Container.WAV,
        Container.AAC_ADTS,
        Container.MKV,
        Container.MP3,
        Container.GIF,
        Container.IMAGE_SEQUENCE,
        -> null
    }
}
