package org.libremediaconverter.join

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import org.libremediaconverter.convert.InputFile
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.ui.PrimaryButtonHeight
import org.libremediaconverter.ui.ScreenPaddingHorizontal
import org.libremediaconverter.ui.ScreenPaddingVertical
import org.libremediaconverter.ui.TestTags
import org.libremediaconverter.work.ConcatWorker

@UnstableApi
@Composable
fun JoinScreen(modifier: Modifier = Modifier, viewModel: JoinViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickInputs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.onInputsPicked(uris) }

    // The contract's MIME type comes from the finished job rather than from a literal: some
    // providers rewrite a document's extension to match it, so naming MP4 for a join that is not
    // one can hand the user a file the extension lies about. Remembered against that type so the
    // launcher re-registers only when it actually changes.
    val destinationMime = (state as? JoinState.Joined)?.mimeType ?: ConcatWorker.DEFAULT_FORMAT.mimeType
    val chooseDestination = rememberLauncherForActivityResult(
        remember(destinationMime) { ActivityResultContracts.CreateDocument(destinationMime) },
    ) { uri -> uri?.let(viewModel::save) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPaddingHorizontal, vertical = ScreenPaddingVertical),
    ) {
        Text(
            "Join files",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Same split as the converter screen: the empty state is centred, the working
        // states scroll because their content can exceed the screen.
        val body = Modifier.fillMaxWidth().weight(1f)

        when (val s = state) {
            is JoinState.Idle -> Column(
                modifier = body,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Pick two or more files to join, in the order you want them.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(
                    onClick = { pickInputs.launch(arrayOf("video/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryButtonHeight)
                        .testTag(TestTags.Join.CHOOSE_FILES),
                ) { Text("Choose files") }
            }

            else -> Column(
                modifier = body.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    is JoinState.Idle -> Unit

                    is JoinState.Ready -> {
                        s.inputs.forEach { FileRow(it) }
                        Button(
                            onClick = viewModel::join,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.Join.JOIN),
                        ) { Text("Join ${s.inputs.size} files") }
                        OutlinedButton(
                            onClick = { pickInputs.launch(arrayOf("video/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.Join.CHOOSE_DIFFERENT_FILES),
                        ) { Text("Choose different files") }
                    }

                    is JoinState.Joining -> {
                        Text("Joining ${s.inputs.size} files…")
                        // Indeterminate on purpose: FFmpeg reports progress against a
                        // single input's duration, which means nothing across a
                        // concatenation. A fabricated percentage would be worse than none.
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.Join.PROGRESS),
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.CANCEL),
                        ) { Text("Cancel") }
                    }

                    is JoinState.Waiting -> {
                        // Same two causes as the converter screen's Waiting state, and the same
                        // wording for them -- see the comment there.
                        Text(
                            "Paused. Android limits background media processing, so this will " +
                                "resume automatically — keeping the app open helps it along.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.CANCEL),
                        ) { Text("Cancel") }
                    }

                    is JoinState.Joined -> {
                        Text("Joined — ${s.staged.length() / 1_000_000} MB.")
                        Text(
                            when (s.strategy) {
                                ConcatStrategy.STREAM_COPY ->
                                    "Files matched, so they were joined without " +
                                        "re-encoding — no quality loss."
                                ConcatStrategy.REENCODE ->
                                    "Files differed in format, so they were re-encoded " +
                                        "to match."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { chooseDestination.launch(s.suggestedName) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.SAVE_FILE),
                        ) { Text("Save file") }
                        OutlinedButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.START_OVER),
                        ) { Text("Start over") }
                    }

                    is JoinState.Saved -> {
                        Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::reset,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.Join.JOIN_MORE),
                        ) { Text("Join more") }
                    }

                    is JoinState.Failed -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = viewModel::reset,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.START_OVER),
                        ) { Text("Start over") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileRow(input: InputFile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.Join.fileRow(input.displayName)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(input.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
