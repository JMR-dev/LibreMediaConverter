package dev.jasonmross.mediaconverter.ffmpeg

import dev.jasonmross.mediaconverter.model.ConcatStrategy
import dev.jasonmross.mediaconverter.model.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FFmpegConcatCommandTest {

    private val listFile = File("/cache/list.txt")
    private val output = File("/cache/joined.mp4")
    private val inputs = listOf("/cache/a.mp4", "/cache/b.mp4")

    @Test
    fun `list file quotes each entry for the concat demuxer`() {
        val text = FFmpegConcatCommand.listFileContents(inputs)
        assertEquals("file '/cache/a.mp4'\nfile '/cache/b.mp4'\n", text)
    }

    @Test
    fun `apostrophes in filenames are escaped`() {
        // An unescaped apostrophe silently truncates the entry, so the file would be
        // skipped rather than the command failing.
        val text = FFmpegConcatCommand.listFileContents(listOf("/cache/jason's clip.mp4"))
        assertTrue("apostrophe not escaped: $text", text.contains("""jason'\''s clip.mp4"""))
    }

    @Test
    fun `stream copy uses the concat demuxer and copies codecs`() {
        val args = FFmpegConcatCommand.build(
            ConcatStrategy.STREAM_COPY, inputs, listFile, output, OutputFormat.MP4_H264,
        )
        assertTrue(args.contains("concat"))
        assertEquals("copy", args[args.indexOf("-c") + 1])
        assertEquals(listFile.absolutePath, args[args.indexOf("-i") + 1])
        assertFalse("stream copy must not re-encode", args.contains("libx264"))
    }

    @Test
    fun `stream copy allows absolute paths in the list file`() {
        val args = FFmpegConcatCommand.build(
            ConcatStrategy.STREAM_COPY, inputs, listFile, output, OutputFormat.MP4_H264,
        )
        // Without -safe 0 the demuxer rejects the absolute paths we generate.
        assertEquals("0", args[args.indexOf("-safe") + 1])
    }

    @Test
    fun `re-encode passes every input separately and builds a filter graph`() {
        val args = FFmpegConcatCommand.build(
            ConcatStrategy.REENCODE, inputs, listFile, output, OutputFormat.MP4_H264,
        )
        assertEquals(2, args.count { it == "-i" })
        val filter = args[args.indexOf("-filter_complex") + 1]
        assertTrue("missing concat filter: $filter", filter.contains("concat=n=2:v=1:a=1"))
        assertTrue("inputs must be normalised before joining", filter.contains("scale="))
        assertTrue("frame rates must be normalised", filter.contains("fps=30"))
        assertTrue(args.contains("libx264"))
    }

    @Test
    fun `re-encode maps the filter outputs rather than raw streams`() {
        val args = FFmpegConcatCommand.build(
            ConcatStrategy.REENCODE, inputs, listFile, output, OutputFormat.MP4_H264,
        )
        assertTrue(args.contains("[v]"))
        assertTrue(args.contains("[a]"))
    }

    @Test
    fun `mp4 output gets faststart on both strategies`() {
        listOf(ConcatStrategy.STREAM_COPY, ConcatStrategy.REENCODE).forEach { strategy ->
            val args = FFmpegConcatCommand.build(strategy, inputs, listFile, output, OutputFormat.MP4_H264)
            assertTrue("$strategy missing faststart", args.contains("+faststart"))
        }
    }

    @Test
    fun `mkv output does not get faststart`() {
        val args = FFmpegConcatCommand.build(
            ConcatStrategy.STREAM_COPY, inputs, listFile, File("/cache/j.mkv"), OutputFormat.MKV_H264,
        )
        assertFalse(args.contains("+faststart"))
    }

    @Test
    fun `output path is always last`() {
        listOf(ConcatStrategy.STREAM_COPY, ConcatStrategy.REENCODE).forEach { strategy ->
            val args = FFmpegConcatCommand.build(strategy, inputs, listFile, output, OutputFormat.MP4_H264)
            assertEquals(output.absolutePath, args.last())
        }
    }
}
