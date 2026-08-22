package org.libremediaconverter.convert

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRouter

/**
 * Guards the agreement between the router's container set and the muxers that back it.
 *
 * These two drifted once already, and the failure was invisible: [ConversionRouter] correctly
 * claimed WebM, Ogg, WAV and AAC for Media3 while [Media3Engine] had no way to write any of them
 * and produced MP4 regardless. Nothing failed — the file was simply the wrong container.
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

    @Test
    fun `WebM advertises only the codecs its muxer accepts`() {
        val factory = requireNotNull(Media3Muxers.factoryFor(Container.WEBM))

        assertEquals(
            listOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9),
            factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO),
        )
        assertEquals(
            listOf(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS),
            factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO),
        )
    }

    /**
     * The audio-only containers must not claim a video track.
     *
     * Transformer reads these lists to decide what it may hand the muxer. Claiming video for a
     * container that cannot hold it turns a routing mistake into a corrupt file rather than a
     * clean failure.
     */
    @Test
    fun `audio-only containers advertise no video codecs`() {
        listOf(Container.OGG, Container.WAV, Container.AAC_ADTS).forEach { container ->
            val factory = requireNotNull(Media3Muxers.factoryFor(container))
            assertTrue(
                "$container claims video support",
                factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO).isEmpty(),
            )
            assertTrue(
                "$container claims no audio support",
                factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO).isNotEmpty(),
            )
        }
    }

    @Test
    fun `WAV carries PCM and AAC-ADTS carries AAC`() {
        assertEquals(
            listOf(MimeTypes.AUDIO_RAW),
            requireNotNull(Media3Muxers.factoryFor(Container.WAV))
                .getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO),
        )
        assertEquals(
            listOf(MimeTypes.AUDIO_AAC),
            requireNotNull(Media3Muxers.factoryFor(Container.AAC_ADTS))
                .getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO),
        )
    }
}
