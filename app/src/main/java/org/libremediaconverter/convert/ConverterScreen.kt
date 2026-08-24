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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import org.libremediaconverter.ui.TestTags
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

    // The contract's MIME type comes from the finished job rather than from the picker as it
    // stands: some providers rewrite a document's extension to match it, so an MP3 offered as
    // video/webm can arrive with the wrong one. Read straight off the collected state, so this
    // recomposes because it depends on that rather than because an unrelated line happens to.
    // Remembered against the type so the launcher re-registers only when it actually changes.
    val destinationMime = (state as? ConversionState.Converted)?.mimeType ?: settings.spec.mimeType
    val chooseDestination = rememberLauncherForActivityResult(
        remember(destinationMime) { ActivityResultContracts.CreateDocument(destinationMime) },
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
                        .height(PrimaryButtonHeight)
                        .testTag(TestTags.Converter.CHOOSE_FILE),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.Converter.CONVERT),
                        ) { Text("Convert") }
                        OutlinedButton(
                            onClick = { pickInput.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.Converter.CHOOSE_DIFFERENT_FILE),
                        ) { Text("Choose a different file") }
                    }

                    is ConversionState.Converting -> {
                        FileCard(s.input)
                        Text("Converting… ${s.percent}%")
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.Converter.PROGRESS),
                        )
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.CANCEL),
                        ) { Text("Cancel") }
                    }

                    is ConversionState.Waiting -> {
                        FileCard(s.input)
                        // Two different causes land here and the state cannot tell them apart:
                        // the six-hour-a-day background media budget running out, and the system
                        // refusing to let a job restart while the app is in the background. The
                        // old wording named only the first, which is now the less likely of the
                        // two. "Keeping the app open helps" covers both -- it is literally what
                        // grants the second one permission to run.
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

                    is ConversionState.Saved -> {
                        Text("Saved ${s.displayName}.", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::reset,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PrimaryButtonHeight)
                                .testTag(TestTags.Converter.CONVERT_ANOTHER),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatPicker(selected: OutputFormat?, onSelect: (OutputFormat) -> Unit) {
    Text("Output format", style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(TestTags.Converter.FORMAT_CHIPS),
    ) {
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
internal fun AdvancedPicker(
    spec: OutputSpec,
    validation: Validation,
    onContainer: (Container) -> Unit,
    onVideoCodec: (VideoCodec) -> Unit,
    onAudioCodec: (AudioCodec) -> Unit,
    onSuggestion: (OutputSpec) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.testTag(TestTags.Converter.ADVANCED_TOGGLE),
    ) {
        Text(if (expanded) "Hide advanced" else "Advanced")
    }

    AnimatedVisibility(visible = expanded) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag(TestTags.Converter.ADVANCED_PANEL),
        ) {
            Text("Container", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(TestTags.Converter.ADVANCED_CONTAINER_CHIPS),
            ) {
                Container.entries.forEach { container ->
                    FilterChip(
                        selected = container == spec.container,
                        onClick = { onContainer(container) },
                        label = { Text(container.label) },
                    )
                }
            }

            Text("Video", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(TestTags.Converter.ADVANCED_VIDEO_CHIPS),
            ) {
                VideoCodec.entries.forEach { codec ->
                    FilterChip(
                        selected = codec == spec.videoCodec,
                        onClick = { onVideoCodec(codec) },
                        label = { Text(codec.label) },
                    )
                }
            }

            Text("Audio", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(TestTags.Converter.ADVANCED_AUDIO_CHIPS),
            ) {
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
internal fun ValidationError(invalid: Validation.Invalid, onSuggestion: (OutputSpec) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.Converter.VALIDATION_ERROR),
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
                    invalid.suggestions.forEachIndexed { index, suggestion ->
                        AssistChip(
                            onClick = { onSuggestion(suggestion) },
                            label = { Text(describe(suggestion)) },
                            modifier = Modifier.testTag(TestTags.Converter.suggestion(index)),
                        )
                    }
                }
            }
        }
    }
}

internal fun describe(spec: OutputSpec): String {
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
internal fun QualityPicker(selected: QualityTier, onSelect: (QualityTier) -> Unit) {
    Text("Quality", style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(TestTags.Converter.QUALITY_CHIPS),
    ) {
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
internal fun EnginePicker(selected: EnginePreference, onSelect: (EnginePreference) -> Unit) {
    Text("Engine", style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(TestTags.Converter.ENGINE_CHIPS),
    ) {
        EnginePreference.entries.forEach { preference ->
            FilterChip(
                selected = preference == selected,
                onClick = { onSelect(preference) },
                label = { Text(preference.label()) },
            )
        }
    }
}

internal fun EnginePreference.label(): String = when (this) {
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
internal fun FileCard(input: InputFile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.Converter.FILE_CARD),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                input.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TestTags.Converter.FILE_CARD_NAME),
            )
            // The null is handled here rather than inside formatBytes, because "no provider would
            // say" is not a number and a formatter that invented one -- "0 B" -- is the defect
            // this card would be showing. It degrades in words, like the codec rows below it.
            Text(
                input.sizeBytes?.let(::formatBytes) ?: "Size unknown",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TestTags.Converter.FILE_CARD_BYTES),
            )

            val probe = input.probe
            if (probe == null) {
                Text(
                    "Reading…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TestTags.Converter.FILE_CARD_NOTE),
                )
                return@Column
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            when (probe.kind) {
                InputKind.UNPARSEABLE -> Text(
                    "Could not identify this file. It will be converted with FFmpeg.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TestTags.Converter.FILE_CARD_NOTE),
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
internal fun DetailRow(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TestTags.Converter.detailRow(label)),
    )
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}
