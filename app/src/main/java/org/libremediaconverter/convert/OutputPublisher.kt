package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Staging and publication of conversion output.
 *
 * Conversions never write directly to the destination the user picked. FFmpeg and the
 * MP4 muxer both need to seek backwards to finalise a file — faststart rewrites the
 * moov atom at the end — and a SAF file descriptor is not reliably seekable. Writing
 * through one produces a truncated or unplayable file.
 *
 * So every job writes to app-private cache, which is a real POSIX path with no
 * permissions and no scoped-storage rules, and the finished file is copied out to the
 * user's chosen destination afterwards.
 *
 * The cost is one extra copy and transient double disk usage, which is why
 * [hasSpaceFor] exists.
 */
open class OutputPublisher(private val context: Context) {

    private val stagingDir: File
        get() = File(context.cacheDir, "conversions").apply { mkdirs() }

    open fun createStagingFile(name: String): File = File(stagingDir, name)

    /**
     * True if there is room for a further [bytes], including headroom.
     *
     * Staging means peak usage is roughly input + output at once, so a job that would
     * just barely fit is rejected rather than failing partway through.
     */
    open fun hasSpaceFor(bytes: Long): Boolean = stagingDir.usableSpace > bytes + SPACE_HEADROOM_BYTES

    /** Copies a finished staging file into a user-chosen SAF destination. */
    open fun publish(staged: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            staged.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open destination for writing: $destination")
    }

    fun clearStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val SPACE_HEADROOM_BYTES = 128L * 1024 * 1024
    }
}
