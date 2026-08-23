package org.libremediaconverter.work

import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything WorkManager still knows about one kind of this app's jobs.
 *
 * The framework half of reattachment, kept deliberately free of decisions: it queries, reads
 * fields across, and stats one file. Which job to pick — and whether any of them is worth
 * picking — is [Reattachment.choose], which is pure and tested on the JVM.
 *
 * `tag` is the worker's class name, which needs no cooperation from the enqueueing code:
 * `WorkRequest.Builder` seeds every request's tag set with `workerClass.name`. That is what
 * makes work enqueued by a previous run of the app — or a previous version of it — findable
 * at all. R8 keeps those names (`-keepnames class * extends androidx.work.ListenableWorker`,
 * from work-runtime's own consumer rules), so the key is stable in a minified build.
 *
 * Runs on [Dispatchers.IO] because it stats a file, and it does so here rather than at the
 * call site so no caller can forget.
 */
suspend fun WorkManager.jobSnapshots(tag: String, outputPathKey: String): List<JobSnapshot> =
    withContext(Dispatchers.IO) {
        getWorkInfosByTagFlow(tag).first().map { info ->
            val path = info.outputData.getString(outputPathKey)
            // An empty file is treated as no file: it would publish as a zero-byte "conversion"
            // rather than fail, which is worse than not offering it at all.
            val output = path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
            JobSnapshot(
                id = info.id,
                state = info.state,
                runAttemptCount = info.runAttemptCount,
                outputPath = path,
                outputExists = output != null,
                // Read here because this is the only place a clock is available at all: it is
                // the sole way to tell two of the app's results apart. See Reattachment.choose.
                outputModifiedAt = output?.lastModified() ?: 0L,
                tags = info.tags,
            )
        }
    }
