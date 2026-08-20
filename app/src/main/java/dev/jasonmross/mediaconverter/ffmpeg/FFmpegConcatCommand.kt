package dev.jasonmross.mediaconverter.ffmpeg

import dev.jasonmross.mediaconverter.model.ConcatStrategy
import dev.jasonmross.mediaconverter.model.OutputFormat
import java.io.File

/**
 * Builds the two different FFmpeg invocations for joining files.
 *
 * Pure so the argument shapes can be unit tested; the caller supplies paths.
 */
object FFmpegConcatCommand {

    /**
     * Content of the list file the `concat` demuxer reads.
     *
     * Single quotes are escaped the way the demuxer expects, because file names
     * routinely contain apostrophes and an unescaped one silently truncates the entry.
     */
    fun listFileContents(paths: List<String>): String =
        paths.joinToString("\n") { "file '${it.replace("'", "'\\''")}'" } + "\n"

    fun build(
        strategy: ConcatStrategy,
        inputPaths: List<String>,
        listFile: File,
        output: File,
        format: OutputFormat,
    ): List<String> = when (strategy) {
        ConcatStrategy.STREAM_COPY -> buildList {
            add("-hide_banner"); add("-y")
            // -safe 0 permits absolute paths in the list file, which ours are.
            add("-f"); add("concat")
            add("-safe"); add("0")
            add("-i"); add(listFile.absolutePath)
            add("-c"); add("copy")
            if (format.container == dev.jasonmross.mediaconverter.model.Container.MP4) {
                add("-movflags"); add("+faststart")
            }
            add(output.absolutePath)
        }

        ConcatStrategy.REENCODE -> buildList {
            add("-hide_banner"); add("-y")
            inputPaths.forEach { add("-i"); add(it) }
            // Normalise every input to a common size and frame rate before joining,
            // otherwise the concat filter refuses mismatched inputs.
            val filter = buildString {
                inputPaths.indices.forEach { i ->
                    append("[$i:v]scale=1280:720:force_original_aspect_ratio=decrease,")
                    append("pad=1280:720:-1:-1,setsar=1,fps=30[v$i];")
                }
                inputPaths.indices.forEach { i -> append("[v$i][$i:a]") }
                append("concat=n=${inputPaths.size}:v=1:a=1[v][a]")
            }
            add("-filter_complex"); add(filter)
            add("-map"); add("[v]")
            add("-map"); add("[a]")
            add("-c:v"); add("libx264")
            add("-crf"); add("20")
            add("-pix_fmt"); add("yuv420p")
            add("-c:a"); add("aac")
            if (format.container == dev.jasonmross.mediaconverter.model.Container.MP4) {
                add("-movflags"); add("+faststart")
            }
            add(output.absolutePath)
        }
    }
}
