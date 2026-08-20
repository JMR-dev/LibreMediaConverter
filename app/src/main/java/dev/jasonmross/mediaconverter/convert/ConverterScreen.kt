package dev.jasonmross.mediaconverter.convert

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import java.util.Locale

@UnstableApi
@Composable
fun ConverterScreen(
    modifier: Modifier = Modifier,
    viewModel: ConversionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ACTION_OPEN_DOCUMENT rather than the photo picker: the picker is images and video
    // only, offers no audio at all, and will not reliably surface .mkv/.flac/.webm.
    // SAF needs no runtime permission.
    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onInputPicked) }

    val chooseDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri -> uri?.let(viewModel::save) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Media Converter", style = MaterialTheme.typography.headlineMedium)

        when (val s = state) {
            is ConversionState.Idle -> {
                Text(
                    "Pick a video to transcode to H.265.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { pickInput.launch(arrayOf("video/*")) }) {
                    Text("Choose file")
                }
            }

            is ConversionState.Ready -> {
                FileCard(s.input)
                Button(onClick = viewModel::convert) { Text("Convert") }
                OutlinedButton(onClick = { pickInput.launch(arrayOf("video/*")) }) {
                    Text("Choose a different file")
                }
            }

            is ConversionState.Converting -> {
                FileCard(s.input)
                Text("Converting… ${s.percent}%")
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ConversionState.Converted -> {
                FileCard(s.input)
                Text(
                    "Done in ${formatSeconds(s.elapsedMs)} — " +
                        "${formatBytes(s.staged.length())} output.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { chooseDestination.launch(viewModel.suggestedOutputName()) }) {
                    Text("Save file")
                }
            }

            is ConversionState.Saved -> {
                Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::reset) { Text("Convert another") }
            }

            is ConversionState.Failed -> {
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
private fun FileCard(input: InputFile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(input.displayName, style = MaterialTheme.typography.titleMedium)
            Text(formatBytes(input.sizeBytes), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}

private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)
