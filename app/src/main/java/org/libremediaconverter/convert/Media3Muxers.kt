package org.libremediaconverter.convert

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.AacMuxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.OggMuxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WavMuxer
import androidx.media3.muxer.WebmMuxer
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList
import org.libremediaconverter.model.Container
import java.io.FileOutputStream

/**
 * Muxer factories for the containers Media3 can write.
 *
 * `media3-muxer` ships `Mp4Muxer`, `WebmMuxer`, `OggMuxer`, `WavMuxer` and `AacMuxer`, but
 * `media3-transformer` only wraps the MP4 ones as a [Muxer.Factory]. Everything else needs the
 * three lines of glue below, which is why the app previously wrote MP4 no matter what container
 * was asked for: [androidx.media3.transformer.Transformer.Builder] defaults to
 * `DefaultMuxer.Factory`, and nothing ever overrode it.
 *
 * ## The MIME lists are load-bearing
 *
 * `Transformer` calls [Muxer.Factory.getSupportedSampleMimeTypes] to decide whether a track can be
 * copied through or has to be re-encoded, and `Transformer.Builder.build()` validates the
 * requested MIME types against it. A list that over-claims produces a file the muxer cannot
 * actually write; one that under-claims forces a needless re-encode. The values here were read out
 * of each muxer's own `isMimeTypeSupported` check rather than assumed.
 */
@UnstableApi
object Media3Muxers {

    /**
     * The factory for [container], or null when Media3 cannot mux it at all.
     *
     * A null here must agree with [org.libremediaconverter.model.ConversionRouter]'s container set
     * — if the router sends Media3 a job this cannot mux, the conversion fails and falls back to
     * FFmpeg, which is a slow way to discover a routing bug. `Media3MuxersTest` asserts they agree.
     */
    fun factoryFor(container: Container): Muxer.Factory? = when (container) {
        // Transformer's own default. Named explicitly so the MP4 path reads the same as the rest.
        Container.MP4 -> DefaultMuxer.Factory()
        Container.WEBM -> WebmFactory
        Container.OGG -> OggFactory
        Container.WAV -> WavFactory
        Container.AAC_ADTS -> AacFactory
        // Matroska, MP3 and the image outputs have no Media3 muxer. FFmpeg owns them.
        Container.MKV,
        Container.MP3,
        Container.GIF,
        Container.IMAGE_SEQUENCE,
        -> null
    }

    private object WebmFactory : Muxer.Factory {
        override fun create(path: String): Muxer = wrapFailure(path) {
            WebmMuxer.Builder(SeekableMuxerOutput.of(path)).build()
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            when (trackType) {
                C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
                C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
                else -> ImmutableList.of()
            }
    }

    private object OggFactory : Muxer.Factory {
        override fun create(path: String): Muxer = wrapFailure(path) {
            OggMuxer.Builder(FileOutputStream(path).channel).build()
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            if (trackType == C.TRACK_TYPE_AUDIO) {
                ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            } else {
                ImmutableList.of()
            }
    }

    private object WavFactory : Muxer.Factory {
        override fun create(path: String): Muxer = wrapFailure(path) {
            WavMuxer(SeekableMuxerOutput.of(path))
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            if (trackType == C.TRACK_TYPE_AUDIO) {
                ImmutableList.of(MimeTypes.AUDIO_RAW)
            } else {
                ImmutableList.of()
            }
    }

    private object AacFactory : Muxer.Factory {
        override fun create(path: String): Muxer = wrapFailure(path) {
            AacMuxer(FileOutputStream(path))
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            if (trackType == C.TRACK_TYPE_AUDIO) {
                ImmutableList.of(MimeTypes.AUDIO_AAC)
            } else {
                ImmutableList.of()
            }
    }

    /**
     * Turns an I/O failure into a [MuxerException].
     *
     * Opening the output can throw `FileNotFoundException`, which is not what `Muxer.Factory`
     * declares. Transformer's error handling only recognises `MuxerException`, so letting the raw
     * IOException escape turns a bad output path into an unhandled crash instead of a reported
     * export failure — the case `Media3EngineTest.anUnwritableOutputPathFailsInsteadOfHanging`
     * exists to pin down.
     */
    private inline fun wrapFailure(path: String, open: () -> Muxer): Muxer =
        try {
            open()
        } catch (e: Exception) {
            if (e is MuxerException) throw e
            throw MuxerException("Could not open $path for muxing", e)
        }
}
