package org.libremediaconverter.convert

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.CodecNames
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.PrimaryButtonHeight
import org.libremediaconverter.ui.ScreenPaddingHorizontal
import org.libremediaconverter.ui.ScreenPaddingVertical
import java.util.Locale

@UnstableApi
@Composable
fun ConverterScreen(modifier: Modifier = Modifier, viewModel: ConversionViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val validation by viewModel.validation.collectAsStateWithLifecycle()

    // ACTION_OPEN_DOCUMENT rather than the photo picker: the picker is images and video
    // only, offers no audio at all, and will not reliably surface .mkv/.flac/.webm.
    // SAF needs no runtime permission.
    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onInputPicked) }

    val chooseDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(settings.spec.mimeType),
    ) { uri -> uri?.let(viewModel::save) }

    // Requested at the point of use rather than on first launch, so the ask carries its
    // own justification. The conversion starts either way: without the permission the
    // foreground service still runs, but its progress notification is confined to the
    // Task Manager instead of the shade.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.convert() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPaddingHorizontal, vertical = ScreenPaddingVertical),
    ) {
        Text(
            "LibreMediaConverter",
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
                        FormatPicker(settings.matchingPreset, viewModel::setPreset)
                        AdvancedPicker(
                            spec = settings.spec,
                            validation = validation,
                            onContainer = viewModel::setContainer,
                            onVideoCodec = viewModel::setVideoCodec,
                            onAudioCodec = viewModel::setAudioCodec,
                            onSuggestion = viewModel::applySuggestion,
                        )
                        QualityPicker(settings.quality, viewModel::setQuality)
                        EnginePicker(settings.enginePreference, viewModel::setEnginePreference)
                        Button(
                            onClick = {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            // The Advanced picker lets an impossible combination be selected on
                            // purpose, so this is what stops it from being run.
                            enabled = validation.isValid,
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
                            // explains why a job was slow, makes the software fallback
                            // visible, and is how the user learns a remux happened rather
                            // than a re-encode.
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
private fun FormatPicker(selected: OutputFormat?, onSelect: (OutputFormat) -> Unit) {
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
    if (selected == null) {
        Text(
            "Custom — set below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full container × codec matrix.
 *
 * Every combination stays selectable, including the ones that cannot work. Disabling or hiding
 * them would leave the user guessing why the option they wanted is not there; letting them pick it
 * and then saying what is wrong — and what would work instead — teaches the constraint. The
 * Convert button is what actually blocks the job.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedPicker(
    spec: OutputSpec,
    validation: Validation,
    onContainer: (Container) -> Unit,
    onVideoCodec: (VideoCodec) -> Unit,
    onAudioCodec: (AudioCodec) -> Unit,
    onSuggestion: (OutputSpec) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide advanced" else "Advanced")
    }

    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Container", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Container.entries.forEach { container ->
                    FilterChip(
                        selected = container == spec.container,
                        onClick = { onContainer(container) },
                        label = { Text(container.label) },
                    )
                }
            }

            Text("Video", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoCodec.entries.forEach { codec ->
                    FilterChip(
                        selected = codec == spec.videoCodec,
                        onClick = { onVideoCodec(codec) },
                        label = { Text(codec.label) },
                    )
                }
            }

            Text("Audio", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioCodec.entries.forEach { codec ->
                    FilterChip(
                        selected = codec == spec.audioCodec,
                        onClick = { onAudioCodec(codec) },
                        label = { Text(codec.label) },
                    )
                }
            }

            Text(
                "Copy keeps the original stream — no re-encoding, so it finishes in seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (validation is Validation.Invalid) {
        ValidationError(validation, onSuggestion)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValidationError(invalid: Validation.Invalid, onSuggestion: (OutputSpec) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(invalid.message, style = MaterialTheme.typography.bodyMedium)
            if (invalid.suggestions.isNotEmpty()) {
                Text("Try instead:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    invalid.suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { onSuggestion(suggestion) },
                            label = { Text(describe(suggestion)) },
                        )
                    }
                }
            }
        }
    }
}

private fun describe(spec: OutputSpec): String {
    val video = when (spec.videoCodec) {
        VideoCodec.NONE -> null
        else -> spec.videoCodec.label
    }
    val audio = when (spec.audioCodec) {
        AudioCodec.NONE -> null
        else -> spec.audioCodec.label
    }
    val tracks = listOfNotNull(video, audio).joinToString(" + ")
    return if (tracks.isEmpty()) spec.container.label else "${spec.container.label} · $tracks"
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

/**
 * Name, size, and what the file actually turned out to be.
 *
 * The codec lines are what make "Copy" a meaningful choice — without knowing the source is H.264,
 * "copy the video" is a guess. They degrade explicitly rather than silently: an audio file says so
 * instead of showing a blank video row, and a file nothing could read says that rather than
 * pretending it has an unknown codec.
 */
@Composable
private fun FileCard(input: InputFile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(input.displayName, style = MaterialTheme.typography.titleMedium)
            Text(formatBytes(input.sizeBytes), style = MaterialTheme.typography.bodySmall)

            val probe = input.probe
            if (probe == null) {
                Text("Reading…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            when (probe.kind) {
                InputKind.UNPARSEABLE -> Text(
                    "Could not identify this file. It will be converted with FFmpeg.",
                    style = MaterialTheme.typography.bodySmall,
                )

                InputKind.IMAGE -> {
                    DetailRow("Type", "Image")
                    if (probe.width > 0) DetailRow("Size", "${probe.width}×${probe.height}")
                }

                InputKind.AUDIO_ONLY -> {
                    DetailRow("Container", probe.container?.label ?: "Unknown")
                    DetailRow("Video", "No video track")
                    DetailRow("Audio", CodecNames.describeAudio(probe.audioCodec))
                    if (probe.durationMs > 0) DetailRow("Length", formatDuration(probe.durationMs))
                }

                InputKind.VIDEO -> {
                    DetailRow("Container", probe.container?.label ?: "Unknown")
                    DetailRow(
                        "Video",
                        buildString {
                            append(CodecNames.describeVideo(probe.videoCodec))
                            if (probe.width > 0) append(" · ${probe.width}×${probe.height}")
                        },
                    )
                    DetailRow(
                        "Audio",
                        if (probe.audioCodec == null) {
                            "No audio track"
                        } else {
                            CodecNames.describeAudio(probe.audioCodec)
                        },
                    )
                    if (probe.durationMs > 0) DetailRow("Length", formatDuration(probe.durationMs))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}
