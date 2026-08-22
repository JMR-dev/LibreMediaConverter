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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@UnstableApi
@Composable
fun JoinScreen(
    modifier: Modifier = Modifier,
    viewModel: JoinViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickInputs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.onInputsPicked(uris) }

    val chooseDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
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
                        .height(PrimaryButtonHeight),
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
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Join ${s.inputs.size} files") }
                        OutlinedButton(
                            onClick = { pickInputs.launch(arrayOf("video/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose different files") }
                    }

                    is JoinState.Joining -> {
                        Text("Joining ${s.inputs.size} files…")
                        // Indeterminate on purpose: FFmpeg reports progress against a
                        // single input's duration, which means nothing across a
                        // concatenation. A fabricated percentage would be worse than none.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }

                    is JoinState.Waiting -> {
                        Text(
                            "Paused. The system limits background media processing to " +
                                "six hours a day, so this will resume automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth(),
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
                            onClick = { chooseDestination.launch("joined.mp4") },
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Save file") }
                        OutlinedButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Start over") }
                    }

                    is JoinState.Saved -> {
                        Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::reset,
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
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
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Start over") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(input: InputFile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(input.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
