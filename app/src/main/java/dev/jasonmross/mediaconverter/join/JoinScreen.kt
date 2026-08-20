package dev.jasonmross.mediaconverter.join

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import dev.jasonmross.mediaconverter.convert.InputFile
import dev.jasonmross.mediaconverter.model.ConcatStrategy

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
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Join files", style = MaterialTheme.typography.headlineMedium)

        when (val s = state) {
            is JoinState.Idle -> {
                Text(
                    "Pick two or more files to join, in the order you want them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { pickInputs.launch(arrayOf("video/*")) }) {
                    Text("Choose files")
                }
            }

            is JoinState.Ready -> {
                s.inputs.forEach { FileRow(it) }
                Button(onClick = viewModel::join) { Text("Join ${s.inputs.size} files") }
                OutlinedButton(onClick = { pickInputs.launch(arrayOf("video/*")) }) {
                    Text("Choose different files")
                }
            }

            is JoinState.Joining -> {
                Text("Joining ${s.inputs.size} files…")
                // Indeterminate on purpose: FFmpeg reports progress against a single
                // input's duration, which means nothing across a concatenation. A
                // fabricated percentage would be worse than none.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
            }

            is JoinState.Waiting -> {
                Text(
                    "Paused. The system limits background media processing to six " +
                        "hours a day, so this will resume automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
            }

            is JoinState.Joined -> {
                Text("Joined — ${s.staged.length() / 1_000_000} MB.")
                Text(
                    when (s.strategy) {
                        ConcatStrategy.STREAM_COPY ->
                            "Files matched, so they were joined without re-encoding — no quality loss."
                        ConcatStrategy.REENCODE ->
                            "Files differed in format, so they were re-encoded to match."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { chooseDestination.launch("joined.mp4") }) { Text("Save file") }
                OutlinedButton(onClick = viewModel::reset) { Text("Start over") }
            }

            is JoinState.Saved -> {
                Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::reset) { Text("Join more") }
            }

            is JoinState.Failed -> {
                Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = viewModel::reset) { Text("Start over") }
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
