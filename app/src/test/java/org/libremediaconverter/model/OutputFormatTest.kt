package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the format table itself.
 *
 * The router, the FFmpeg command builder and the output filename all read from these
 * entries, so a wrong extension or a mislabelled codec propagates everywhere.
 */
class OutputFormatTest {

    @Test
    fun `every format has a non-empty label and extension`() {
        OutputFormat.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
            assertTrue("${it.name} has no extension", it.extension.isNotBlank())
            assertFalse("${it.name} extension should not include a dot", it.extension.startsWith("."))
            assertTrue("${it.name} has no mime type", it.mimeType.contains('/'))
        }
    }

    @Test
    fun `audio only formats carry no video codec`() {
        OutputFormat.entries.filter { it.isAudioOnly }.forEach {
            assertEquals("${it.name} should have no video codec", VideoCodec.NONE, it.videoCodec)
        }
    }

    @Test
    fun `image outputs carry neither video nor audio codecs`() {
        OutputFormat.entries.filter { it.isImageOutput }.forEach {
            assertEquals(VideoCodec.NONE, it.videoCodec)
            assertEquals(AudioCodec.NONE, it.audioCodec)
            assertFalse("${it.name} is not audio-only", it.isAudioOnly)
        }
    }

    @Test
    fun `extensions match their containers`() {
        assertEquals("mp4", OutputFormat.MP4_H264.extension)
        assertEquals("mkv", OutputFormat.MKV_H264.extension)
        assertEquals("mp3", OutputFormat.MP3.extension)
        assertEquals("gif", OutputFormat.GIF.extension)
        assertEquals("wav", OutputFormat.WAV.extension)
    }

    @Test
    fun `video formats are not misreported as audio only`() {
        listOf(OutputFormat.MP4_H264, OutputFormat.MP4_H265, OutputFormat.MKV_H265, OutputFormat.WEBM_VP9)
            .forEach { assertFalse("${it.name} should not be audio-only", it.isAudioOnly) }
    }

    @Test
    fun `quality tiers describe themselves for the UI`() {
        QualityTier.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
    }
}
