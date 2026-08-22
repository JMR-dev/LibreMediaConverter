package org.libremediaconverter.work

/**
 * The little that has to travel with a job so the UI can describe it after a restart.
 *
 * `WorkInfo` exposes a job's id, state, tags, progress and output — never the `Data` it was
 * enqueued with. So a ViewModel that finds a job it did not start can see *that* there is a
 * conversion, and where its output went, but not what file it was converting. Tags are the
 * only channel WorkManager gives back, which is why the display name and size ride on them.
 *
 * Deliberately not carried: the input `Uri`. It is the one field nothing in a reattached state
 * reads, and a `content://` grant taken by a picker in a process that no longer exists is not
 * something to hand back to the user as if it still worked.
 *
 * Values are read back leniently — a missing or malformed tag is null, never an exception.
 * Work enqueued by an older version of the app carries none of these, and it is exactly the
 * work most likely to still be sitting in the queue when this code first runs.
 */
object JobTags {

    fun displayName(name: String): String = DISPLAY_NAME + name

    fun sizeBytes(bytes: Long): String = SIZE_BYTES + bytes

    fun inputCount(count: Int): String = INPUT_COUNT + count

    fun displayNameOf(tags: Set<String>): String? = valueOf(tags, DISPLAY_NAME)

    fun sizeBytesOf(tags: Set<String>): Long? = valueOf(tags, SIZE_BYTES)?.toLongOrNull()

    fun inputCountOf(tags: Set<String>): Int? = valueOf(tags, INPUT_COUNT)?.toIntOrNull()

    /**
     * Matching on the whole tag rather than searching within it is what makes a display name
     * safe to carry verbatim: a file called `lmc.size-bytes:9` becomes the tag
     * `lmc.display-name:lmc.size-bytes:9`, which no other prefix matches.
     */
    private fun valueOf(tags: Set<String>, prefix: String): String? =
        tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    // Namespaced so they cannot collide with the worker class name WorkManager tags every
    // request with, which is what makes the job findable in the first place.
    private const val DISPLAY_NAME = "lmc.display-name:"
    private const val SIZE_BYTES = "lmc.size-bytes:"
    private const val INPUT_COUNT = "lmc.input-count:"
}
