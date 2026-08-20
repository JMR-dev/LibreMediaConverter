package dev.jasonmross.mediaconverter.convert

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import dev.jasonmross.mediaconverter.model.EnginePreference
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier
import java.util.Locale

/** Primary actions are taller than the Material default so they read as the main affordance. */
private val PrimaryButtonHeight: Dp = 56.dp

/**
 * Horizontal screen inset.
 *
 * Deliberately tighter than the vertical inset so a full-width primary button reaches
 * close to both edges of the display.
 */
private val ScreenPadding: Dp = 16.dp

@UnstableApi
@Composable
fun ConverterScreen(
    modifier: Modifier = Modifier,
    viewModel: ConversionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // ACTION_OPEN_DOCUMENT rather than the photo picker: the picker is images and video
    // only, offers no audio at all, and will not reliably surface .mkv/.flac/.webm.
    // SAF needs no runtime permission.
    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onInputPicked) }

    val chooseDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(settings.format.mimeType)
    ) { uri -> uri?.let(viewModel::save) }

    // Requested at the point of use rather than on first launch, so the ask carries its
    // own justification. The conversion starts either way: without the permission the
    // foreground service still runs, but its progress notification is confined to the
    // Task Manager instead of the shade.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.convert() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
    ) {
        Text(
            "Media Converter",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // The empty state is centred in whatever space is left. The working states
        // scroll instead, since their content can exceed the screen.
        val body = Modifier.fillMaxWidth().weight(1f)

        when (val s = state) {
            is ConversionState.Idle -> Column(
                modifier = body,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Pick a file to convert.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(
                    onClick = { pickInput.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryButtonHeight),
                ) { Text("Choose file") }
            }

            else -> Column(
                modifier = body.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    is ConversionState.Idle -> Unit

                    is ConversionState.Ready -> {
                        FileCard(s.input)
                        FormatPicker(settings.format, viewModel::setFormat)
                        QualityPicker(settings.quality, viewModel::setQuality)
                        EnginePicker(settings.enginePreference, viewModel::setEnginePreference)
                        Button(
                            onClick = {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Convert") }
                        OutlinedButton(
                            onClick = { pickInput.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose a different file") }
                    }

                    is ConversionState.Converting -> {
                        FileCard(s.input)
                        Text("Converting… ${s.percent}%")
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }

                    is ConversionState.Waiting -> {
                        FileCard(s.input)
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

                    is ConversionState.Converted -> {
                        FileCard(s.input)
                        Text(
                            "Done — ${formatBytes(s.staged.length())} output.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.routeReason.isNotBlank()) {
                            // Surfacing the routing decision rather than hiding it: it
                            // explains why a job was slow, and makes the software
                            // fallback visible.
                            AssistChip(onClick = {}, label = { Text(s.routeReason) })
                        }
                        Button(
                            onClick = { chooseDestination.launch(viewModel.suggestedOutputName()) },
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Save file") }
                        OutlinedButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Start over") }
                    }

                    is ConversionState.Saved -> {
                        Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::reset,
                            modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                        ) { Text("Convert another") }
                    }

                    is ConversionState.Failed -> {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatPicker(selected: OutputFormat, onSelect: (OutputFormat) -> Unit) {
    Text("Output format", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutputFormat.entries.forEach { format ->
            FilterChip(
                selected = format == selected,
                onClick = { onSelect(format) },
                label = { Text(format.label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityPicker(selected: QualityTier, onSelect: (QualityTier) -> Unit) {
    Text("Quality", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QualityTier.entries.forEach { tier ->
            FilterChip(
                selected = tier == selected,
                onClick = { onSelect(tier) },
                label = { Text(tier.label) },
            )
        }
    }
    Text(selected.description, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnginePicker(selected: EnginePreference, onSelect: (EnginePreference) -> Unit) {
    Text("Engine", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnginePreference.entries.forEach { preference ->
            FilterChip(
                selected = preference == selected,
                onClick = { onSelect(preference) },
                label = { Text(preference.label()) },
            )
        }
    }
}

private fun EnginePreference.label(): String = when (this) {
    EnginePreference.AUTO -> "Automatic"
    EnginePreference.PREFER_HARDWARE -> "Prefer hardware"
    EnginePreference.FORCE_SOFTWARE -> "Force software"
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
