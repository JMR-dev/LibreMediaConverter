package org.libremediaconverter.convert

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.CodecMode
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ContainerCapabilities
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.VideoCodec

/**
 * Guards the agreement between the router's container set and the muxers that back it.
 *
 * These two drifted once already, and the failure was invisible in both directions: the router
 * claimed WebM, Ogg, WAV and AAC-ADTS for Media3 — none of which Transformer can actually write —
 * while [Media3Engine] ignored the container and produced MP4 for all of them. Two bugs that
 * cancelled out, so nothing failed and the file was simply the wrong container.
 *
 * A JVM test rather than an instrumented one: constructing a factory touches no Android APIs, only
 * `create()` does, so the mapping is checkable without a device.
 */
@UnstableApi
class Media3MuxersTest {

    @Test
    fun `every container the router sends to Media3 has a muxer factory`() {
        ConversionRouter.MEDIA3_CONTAINERS.forEach { container ->
            assertNotNull(
                "$container is routed to Media3 but has no Muxer.Factory",
                Media3Muxers.factoryFor(container),
            )
        }
    }

    @Test
    fun `containers the router withholds from Media3 have no factory`() {
        Container.entries
            .filter { it !in ConversionRouter.MEDIA3_CONTAINERS }
            .forEach { container ->
                assertNull(
                    "$container has a Muxer.Factory but the router never sends it to Media3",
                    Media3Muxers.factoryFor(container),
                )
            }
    }

    /**
     * MP4 is the whole of it.
     *
     * Pinned as a value rather than left implicit, because `media3-muxer` shipping a `WebmMuxer`,
     * `OggMuxer`, `WavMuxer` and `AacMuxer` makes it look like there should be five. Those four
     * throw `UnsupportedOperationException` from `addMetadataEntry`, which `MuxerWrapper` calls
     * unconditionally — see [Media3Muxers]. If a future Media3 release fixes that, this test is
     * where the decision to widen the set gets made deliberately.
     */
    @Test
    fun `Media3 can write MP4 and nothing else`() {
        assertEquals(setOf(Container.MP4), ConversionRouter.MEDIA3_CONTAINERS)
    }

    /**
     * The router's transcription of what Media3 can carry must match the factories themselves.
     *
     * `ConversionRouter` cannot import a `Muxer.Factory` — `model/` is deliberately free of Android
     * and Media3 types so the routing rules stay JVM-testable — so it restates these sets by hand.
     * Two hand-written answers to one question drift; this is what stops them.
     *
     * The check runs against the factory's own `getSupportedSampleMimeTypes`, which is what
     * `Transformer` consults, so it is ground truth rather than another transcription.
     */
    @Test
    fun `the router's muxable sets match what the factories report`() {
        ConversionRouter.MEDIA3_CONTAINERS.forEach { container ->
            val factory = requireNotNull(Media3Muxers.factoryFor(container))

            val reportedVideo = factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO)
                .mapNotNull(::videoCodecOf).toSet()
            val reportedAudio = factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)
                .mapNotNull(::audioCodecOf).toSet()

            assertEquals(
                "$container video",
                reportedVideo,
                ConversionRouter.MEDIA3_MUXABLE_VIDEO[container].orEmpty(),
            )
            assertEquals(
                "$container audio",
                reportedAudio,
                ConversionRouter.MEDIA3_MUXABLE_AUDIO[container].orEmpty(),
            )
        }
    }

    /**
     * Records the two places Media3's muxers and the app-wide matrix deliberately disagree.
     *
     * Neither is a bug, and pinning them is the point: if either moves, one of the two documents
     * has changed its mind and somebody should say so on purpose.
     *
     * - Media3's MP4 muxer accepts Vorbis; [ContainerCapabilities] declines to offer it, because
     *   Vorbis-in-MP4 is poorly supported by players.
     * - The matrix offers MP3 and FLAC in MP4, which is legal and which FFmpeg writes happily, but
     *   Media3's MP4 muxer carries neither — so those jobs route to FFmpeg rather than failing.
     */
    @Test
    fun `the documented divergences between Media3 and the matrix still hold`() {
        val media3Mp4Audio = ConversionRouter.MEDIA3_MUXABLE_AUDIO.getValue(Container.MP4)

        assertTrue(
            "Media3 still takes Vorbis in MP4",
            AudioCodec.VORBIS in media3Mp4Audio,
        )
        assertFalse(
            "the matrix still declines to offer it",
            ContainerCapabilities.accepts(Container.MP4, AudioCodec.VORBIS, CodecMode.COPY),
        )

        listOf(AudioCodec.MP3, AudioCodec.FLAC).forEach { codec ->
            assertTrue(
                "the matrix still allows $codec in MP4",
                ContainerCapabilities.accepts(Container.MP4, codec, CodecMode.COPY),
            )
            assertFalse(
                "Media3's MP4 muxer still cannot carry $codec",
                codec in media3Mp4Audio,
            )
        }
    }

    private fun videoCodecOf(mime: String): VideoCodec? = when (mime) {
        MimeTypes.VIDEO_H264 -> VideoCodec.H264
        MimeTypes.VIDEO_H265 -> VideoCodec.H265
        MimeTypes.VIDEO_VP8 -> VideoCodec.VP8
        MimeTypes.VIDEO_VP9 -> VideoCodec.VP9
        MimeTypes.VIDEO_AV1 -> VideoCodec.AV1
        else -> null
    }

    private fun audioCodecOf(mime: String): AudioCodec? = when (mime) {
        MimeTypes.AUDIO_AAC -> AudioCodec.AAC
        MimeTypes.AUDIO_OPUS -> AudioCodec.OPUS
        MimeTypes.AUDIO_VORBIS -> AudioCodec.VORBIS
        MimeTypes.AUDIO_RAW -> AudioCodec.PCM
        MimeTypes.AUDIO_MPEG -> AudioCodec.MP3
        MimeTypes.AUDIO_FLAC -> AudioCodec.FLAC
        else -> null
    }
}
