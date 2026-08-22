package org.libremediaconverter.convert

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRouter

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
}
