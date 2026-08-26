package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matrix that replaced the closed format enum.
 *
 * `OutputFormat` used to be twelve hand-picked triples, and its KDoc defended that on the grounds
 * that a closed set was what made routing decidable. Opening it up moves that burden here, so this
 * is where decidability now has to be proven.
 *
 * That includes what a refusal offers instead. `Validation.Invalid` promises every suggestion is
 * itself valid and names this class as the proof, so a branch that assembles its own suggestion
 * list rather than going through `suggestions()` is only checked here if some row happens to reach
 * it — which is how a dead-end chip survived two widenings of that table.
 */
class ContainerCapabilitiesTest {

    private val h264Source = InputProbe(
        videoCodec = "h264",
        audioCodec = "aac",
        container = Container.MP4,
    )

    /**
     * An MP3, and the reason several rules below need a second probe.
     *
     * `hasVideo = false` is the load-bearing field. Every rule that reads only the spec answers the
     * same for this input as for a video file, which is exactly how a spec naming a video codec was
     * called valid for a file with no video track to put in it.
     */
    private val mp3Source = InputProbe(
        videoCodec = null,
        audioCodec = "mp3",
        hasVideo = false,
        kind = InputKind.AUDIO_ONLY,
        container = Container.MP3,
    )

    /**
     * An audio-only input carrying a codec MP4 has no place for at all.
     *
     * Vorbis lives in Ogg and Matroska; MP4 carries AAC, MP3, Opus and FLAC. That gap is what turns
     * a suggestion which merely drops the video track into a second refusal.
     */
    private val vorbisSource = InputProbe(
        videoCodec = null,
        audioCodec = "vorbis",
        hasVideo = false,
        kind = InputKind.AUDIO_ONLY,
        container = Container.OGG,
    )

    /** The same shape, for the other codec MP4 refuses. One case is a coincidence; two is the rule. */
    private val pcmSource = InputProbe(
        videoCodec = null,
        audioCodec = "pcm_s16le",
        hasVideo = false,
        kind = InputKind.AUDIO_ONLY,
        container = Container.WAV,
    )

    // --- copy and encode are different questions ----------------------------

    /**
     * The case that makes the mode axis necessary.
     *
     * A single boolean would have to answer one way or the other, and either answer is wrong half
     * the time: refusing AV1 in MP4 blocks a legitimate remux, allowing it promises an encode
     * neither engine can deliver.
     */
    @Test
    fun `MP4 carries AV1 on copy but cannot encode it`() {
        assertTrue(ContainerCapabilities.accepts(Container.MP4, VideoCodec.AV1, CodecMode.COPY))
        assertFalse(ContainerCapabilities.accepts(Container.MP4, VideoCodec.AV1, CodecMode.ENCODE))
    }

    @Test
    fun `Matroska carries Vorbis on copy but nothing here encodes it`() {
        assertTrue(ContainerCapabilities.accepts(Container.MKV, AudioCodec.VORBIS, CodecMode.COPY))
        assertFalse(
            ContainerCapabilities.accepts(Container.MKV, AudioCodec.VORBIS, CodecMode.ENCODE),
        )
    }

    @Test
    fun `a codec the container cannot hold is refused in both modes`() {
        listOf(CodecMode.COPY, CodecMode.ENCODE).forEach { mode ->
            assertFalse(
                "WebM should never accept H.264 ($mode)",
                ContainerCapabilities.accepts(Container.WEBM, VideoCodec.H264, mode),
            )
            assertFalse(
                "WAV should never accept AAC ($mode)",
                ContainerCapabilities.accepts(Container.WAV, AudioCodec.AAC, mode),
            )
        }
    }

    @Test
    fun `H265 in AVI is refused — AVI predates it`() {
        assertFalse(ContainerCapabilities.accepts(Container.AVI, VideoCodec.H265, CodecMode.COPY))
        assertTrue(ContainerCapabilities.accepts(Container.AVI, VideoCodec.H264, CodecMode.COPY))
    }

    @Test
    fun `resolving COPY before asking the matrix is required`() {
        // The matrix cannot answer for COPY; the caller has to resolve it against the probe first.
        // Failing loudly is what stops a caller from silently getting "false" and refusing a
        // perfectly good remux.
        runCatching { ContainerCapabilities.accepts(Container.MP4, VideoCodec.COPY, CodecMode.COPY) }
            .onSuccess { throw AssertionError("expected COPY to be rejected by the matrix") }
    }

    // --- validation ---------------------------------------------------------

    @Test
    fun `every preset is a valid spec`() {
        OutputFormat.entries.forEach { preset ->
            val result = ContainerCapabilities.validate(preset.spec, h264Source)
            assertTrue("${preset.name} is not valid: $result", result.isValid)
        }
    }

    /**
     * A suggestion that is itself invalid is worse than no suggestion.
     *
     * Only a branch that assembles its own suggestion list can break that promise: [suggestions]
     * ends by filtering on `validate(...).isValid`, so everything routed through it is valid by
     * construction. Those branches are what this table has to cover — the image output, and copy
     * the video from a file that has none, which built its list by hand and came back refused for
     * a Vorbis or PCM source into MP4 and an MP3 into WebM. The Advanced picker showed a one-tap
     * fix that led straight to a second error, through two widenings of this table that never
     * reached the branch.
     */
    @Test
    fun `every suggestion is itself valid`() {
        val cases = listOf(
            OutputSpec(Container.WEBM, VideoCodec.H264, AudioCodec.AAC) to h264Source,
            // The audio-only input. Every rejection it can reach used to hand back `None + None`
            // — a spec validation refuses in the next breath — because these branches built their
            // suggestion by hand instead of going through the repair-and-filter path.
            OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.NONE) to mp3Source,
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.NONE) to mp3Source,
            OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE) to mp3Source,
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.AAC) to mp3Source,
            // Copy-the-video-from-a-file-with-no-video, the last branch that built its offer by
            // hand. It escaped the five rows above because `spec.copy(videoCodec = NONE)` is valid
            // exactly when the audio axis happens to be fine — true for the AAC and MP3 sources
            // used there, false for any audio the target container cannot carry.
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY) to vorbisSource,
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY) to pcmSource,
            OutputSpec(Container.WEBM, VideoCodec.COPY, AudioCodec.COPY) to mp3Source,
            // The same branch with audio the container *can* hold, which is the half that already
            // worked and must keep working: the repair here is a copy, so the offer is the very
            // spec the caller handed to `suggestions`. It survives only because the exclusion is
            // against what the user asked for rather than against the repair.
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY) to mp3Source,
            // The one branch that still builds its list by hand, so that it is asserted rather
            // than merely reasoned about: an image container takes `None + None` and nothing else,
            // which makes its single offer valid by construction.
            OutputSpec(Container.GIF, VideoCodec.H264, AudioCodec.AAC) to h264Source,
        )

        cases.forEach { (spec, probe) ->
            val invalid = ContainerCapabilities.validate(spec, probe) as? Validation.Invalid
                ?: throw AssertionError("expected $spec to be rejected")
            assertTrue("no alternatives offered for $spec on $probe", invalid.suggestions.isNotEmpty())
            invalid.suggestions.forEach { suggestion ->
                assertTrue(
                    "suggested $suggestion for $spec on $probe is itself invalid",
                    ContainerCapabilities.validate(suggestion, probe).isValid,
                )
            }
        }
    }

    @Test
    fun `an unidentifiable source codec cannot be copied`() {
        val unknown = InputProbe(videoCodec = InputProbe.UNPARSEABLE, audioCodec = null)
        val spec = OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.NONE)

        val result = ContainerCapabilities.validate(spec, unknown)
        assertFalse("copying an unknown codec must be refused", result.isValid)
    }

    @Test
    fun `copying a video track into an audio-only container is refused`() {
        val spec = OutputSpec(Container.MP3, VideoCodec.COPY, AudioCodec.MP3)
        val result = ContainerCapabilities.validate(spec, h264Source)

        val invalid = result as? Validation.Invalid
            ?: throw AssertionError("expected video in MP3 to be rejected")
        assertTrue(invalid.message.contains("audio only"))
    }

    @Test
    fun `dropping both tracks is refused rather than producing an empty file`() {
        val spec = OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE)
        val result = ContainerCapabilities.validate(spec, h264Source)

        assertFalse(result.isValid)
        assertTrue((result as Validation.Invalid).suggestions.isNotEmpty())
    }

    /**
     * The same rule, seen only against the probe.
     *
     * A video codec named for a file with no video track is dropped, not encoded — so
     * MP4/H.265/None on an MP3 empties the output exactly as None/None does. Reading the spec
     * alone answered "valid" because the spec names a video codec, and the job went to Media3,
     * where `EditedMediaItem.Builder` refuses a composition with both tracks removed by throwing
     * on Transformer's own HandlerThread.
     */
    @Test
    fun `a video codec named for a file with no video track and no audio is refused`() {
        ContainerCapabilities.encodableVideo(Container.MP4).forEach { codec ->
            val spec = OutputSpec(Container.MP4, codec, AudioCodec.NONE)
            val result = ContainerCapabilities.validate(spec, mp3Source)

            assertFalse(
                "MP4/${codec.label}/None on an audio-only input plans to (Drop, Drop) and would " +
                    "produce an empty file; it must be refused. Got $result",
                result.isValid,
            )
        }
    }

    /**
     * The refusal is only worth having if it leads somewhere.
     *
     * The COPY form of this was already refused, but its one hand-built suggestion was
     * `None + None` — which validation refuses in the next breath, so the Advanced picker offered
     * a one-tap fix that fixed nothing. Every face of the rule now goes through the shared
     * suggestion path, so the offer keeps the one track the input actually has.
     */
    @Test
    fun `refusing an empty output still offers a way to keep the audio`() {
        listOf(VideoCodec.H265, VideoCodec.H264, VideoCodec.COPY, VideoCodec.NONE).forEach { codec ->
            val spec = OutputSpec(Container.MP4, codec, AudioCodec.NONE)
            val invalid = ContainerCapabilities.validate(spec, mp3Source) as? Validation.Invalid
                ?: throw AssertionError("expected MP4/${codec.label}/None to be rejected")

            assertTrue(
                "a refusal with no way out is a dead end in the Advanced picker",
                invalid.suggestions.isNotEmpty(),
            )
            assertTrue(
                "every suggestion must keep a track, got ${invalid.suggestions}",
                invalid.suggestions.all { it.audioCodec != AudioCodec.NONE },
            )
        }
    }

    /**
     * A repair must not name a track the input does not have.
     *
     * `repairVideo` used to fall through to "the first codec this container can encode" whenever
     * nothing else fitted, and for an MP3 that produced the non-sequitur `MP4 · H.264 · Copy`.
     * It validated, so nothing caught it — but [CopyPlanner] drops that video track anyway, which
     * makes the codec in the offer a fiction.
     */
    @Test
    fun `a repair for a file with no video track never names a video codec`() {
        listOf(
            OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.NONE),
            OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE),
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.NONE),
        ).forEach { spec ->
            val invalid = ContainerCapabilities.validate(spec, mp3Source) as Validation.Invalid
            invalid.suggestions.forEach {
                assertEquals(
                    "offering ${it.videoCodec.label} for a file with no video track is a fiction; " +
                        "CopyPlanner drops it. Suggested $it for $spec",
                    VideoCodec.NONE,
                    it.videoCodec,
                )
            }
        }
    }

    /**
     * The rule stated as the property it is, over the whole matrix.
     *
     * A plan of (Drop, Drop) is precisely the composition `EditedMediaItem.Builder` refuses to
     * build, so no non-image spec that reaches it may be called valid. Sweeping every container ×
     * codec × codec against both probes is what stops the next container or codec from
     * reintroducing the gap on an axis nobody thought to write a case for.
     *
     * Image outputs are exempt and deliberately so: GIF and PNG frames carry no codecs at all, and
     * `None + None` is the only spec they accept — but they never reach Media3, because the router
     * sends every image output to FFmpeg.
     */
    @Test
    fun `no valid non-image spec plans to remove both tracks`() {
        val specs = Container.entries
            .filterNot { it == Container.GIF || it == Container.IMAGE_SEQUENCE }
            .flatMap { container -> VideoCodec.entries.map { container to it } }
            .flatMap { (container, video) -> AudioCodec.entries.map { OutputSpec(container, video, it) } }
        val cases = specs.flatMap { spec -> listOf(h264Source, mp3Source).map { spec to it } }

        val empties = cases.filter { (spec, probe) ->
            val plan = CopyPlanner.plan(spec, probe)
            plan.video == VideoPlan.Drop && plan.audio == AudioPlan.Drop
        }

        assertTrue("the sweep found nothing to check — the filter has gone wrong", empties.isNotEmpty())
        empties.forEach { (spec, probe) ->
            assertFalse(
                "$spec on $probe plans to (Drop, Drop) — an empty file, and the composition " +
                    "Media3 cannot build — so it must not validate",
                ContainerCapabilities.validate(spec, probe).isValid,
            )
        }
    }

    @Test
    fun `copying is offered as the fix when the codec is right but unencodable`() {
        val av1Source = InputProbe(videoCodec = "av1", audioCodec = "aac", container = Container.MKV)
        val spec = OutputSpec(Container.MP4, VideoCodec.AV1, AudioCodec.AAC)

        val invalid = ContainerCapabilities.validate(spec, av1Source) as? Validation.Invalid
            ?: throw AssertionError("expected an AV1 encode to be rejected")
        assertTrue(
            "should offer to copy the AV1 track instead, got ${invalid.suggestions}",
            invalid.suggestions.any { it.videoCodec == VideoCodec.COPY },
        )
    }

    // --- the picker reads these ---------------------------------------------

    @Test
    fun `encodable lists never contain a codec the container cannot hold`() {
        Container.entries.forEach { container ->
            ContainerCapabilities.encodableVideo(container).forEach {
                assertTrue(
                    "$container claims to encode $it but cannot hold it",
                    ContainerCapabilities.accepts(container, it, CodecMode.COPY),
                )
            }
            ContainerCapabilities.encodableAudio(container).forEach {
                assertTrue(
                    "$container claims to encode $it but cannot hold it",
                    ContainerCapabilities.accepts(container, it, CodecMode.COPY),
                )
            }
        }
    }

    @Test
    fun `audio-only containers offer no video codecs`() {
        listOf(Container.MP3, Container.WAV, Container.FLAC, Container.OGG, Container.AAC_ADTS)
            .forEach { container ->
                assertFalse("$container claims to hold video", container.canHoldVideo)
                assertEquals(emptyList<VideoCodec>(), ContainerCapabilities.encodableVideo(container))
            }
    }
}
