package org.libremediaconverter.ui

/**
 * Where a test finds each affordance on the two screens.
 *
 * Every button, picker and card in `ConverterScreen` and `JoinScreen` carries one of these through
 * `Modifier.testTag`, so a test names a symbol and never a literal. That is the whole reason the
 * table exists: `"Cancel"`, `"Start over"` and `"Save file"` are each rendered by both screens and
 * by more than one state branch, so rewording one of them would otherwise redden several
 * independent test files at once, and none of those diffs would explain why.
 *
 * Tags are applied inside `main`, never handed in by the caller. A tag a test passes down as a
 * `Modifier` proves only that the test set it -- it would stay green with the affordance's own tag
 * deleted, which is exactly the vacuous test `CLAUDE.md` records nine of.
 *
 * ### Public rather than `internal`, deliberately
 *
 * `androidTest` **is** a friend source set of `main` here: an `androidTest` file referencing the
 * `internal` `Destination.CONVERT` compiles clean through `:app:compileDebugAndroidTestKotlin`
 * under AGP 9.3.1 (measured 2026-08-24 -- nothing in the repo referenced a main `internal` from
 * `androidTest`, so the question had no in-tree answer until then). `internal` would compile today.
 *
 * It is public anyway. That friendship is AGP wiring rather than something this project states, and
 * this table is a contract read from three source sets: `main` applies the tags, `src/test` and
 * `src/androidTest` name them. Public buys no external exposure in an application module -- nothing
 * consumes it from outside -- so the durable answer costs nothing here.
 *
 * ### Invariants
 *
 * Values are distinct, which `TagTableUniquenessTest` asserts. Two affordances sharing a tag would
 * break the "resolves to exactly one node" assertion in a file nobody had touched.
 */
object TestTags {

    /**
     * Affordances both screens render, under one name each.
     *
     * Shared rather than per-screen because only one screen is composed at a time -- the shell
     * swaps them -- so a tag can only ever resolve within the screen under test.
     */
    const val CANCEL: String = "action.cancel"

    /** Rendered by `Converted`/`Joined` and again by `Failed` on both screens. */
    const val START_OVER: String = "action.startOver"

    const val SAVE_FILE: String = "action.saveFile"

    /**
     * The retry a `Failed` offers after a save that threw, on both screens.
     *
     * Its own tag rather than [SAVE_FILE], because the two are different claims about the screen.
     * [SAVE_FILE] is the first attempt from a finished job; this one may appear only where a staged
     * file survived a failed save. Sharing a tag would collapse "a transcode failure offers nothing
     * to save" and "a failed save offers the file again" into one query, and that first assertion
     * is the one stopping a Save button from appearing where there is nothing to save.
     */
    const val RETRY_SAVE: String = "action.retrySave"

    /** `ConverterScreen`. */
    object Converter {
        const val CHOOSE_FILE: String = "converter.chooseFile"
        const val CONVERT: String = "converter.convert"
        const val CHOOSE_DIFFERENT_FILE: String = "converter.chooseDifferentFile"
        const val CONVERT_ANOTHER: String = "converter.convertAnother"

        /** The determinate bar in `Converting`. It carries no text, so nothing else can find it. */
        const val PROGRESS: String = "converter.progress"

        /**
         * The chip on `Converted` that says which engine ran the job and why.
         *
         * Conditional on `routeReason` being non-blank, and that condition is what the tag is for:
         * its text comes from the finished job, so a text matcher looking for it would have to
         * name a routing explanation the screen does not own.
         */
        const val ROUTE_REASON: String = "converter.routeReason"

        const val FILE_CARD: String = "converter.fileCard"
        const val FILE_CARD_NAME: String = "converter.fileCard.name"

        /**
         * The byte size, or `"Size unknown"`.
         *
         * Named for bytes rather than "size" because the `IMAGE` branch also renders a row labelled
         * `Size` -- pixel dimensions -- through [detailRow], and the two mean different things.
         */
        const val FILE_CARD_BYTES: String = "converter.fileCard.bytes"

        /**
         * The one-line explanation that stands in for the detail rows: `"Reading…"` while the probe
         * is still running, or the unreadable-file line once it has finished and found nothing.
         * The two are mutually exclusive, so one tag covers both.
         */
        const val FILE_CARD_NOTE: String = "converter.fileCard.note"

        /**
         * The chip rows, not the pickers around them.
         *
         * Each tag sits on the `FlowRow` of chips, so the prose a picker renders beside it -- the
         * `"Custom — set below."` line under the formats, the tier description under the quality
         * chips -- is outside the tagged node. Tagging the picker as a whole would mean wrapping
         * three sibling emissions in a layout that does not exist today.
         */
        const val FORMAT_CHIPS: String = "converter.formatChips"

        const val QUALITY_CHIPS: String = "converter.qualityChips"
        const val ENGINE_CHIPS: String = "converter.engineChips"

        /** The `Advanced` / `Hide advanced` toggle. Present whether or not the panel is open. */
        const val ADVANCED_TOGGLE: String = "converter.advanced.toggle"

        /** The panel the toggle gates. Absent from the tree while collapsed. */
        const val ADVANCED_PANEL: String = "converter.advanced.panel"

        /**
         * The three chip rows inside the panel, separately.
         *
         * Separately because their labels collide: `"Copy"` and `"None"` are both a `VideoCodec`
         * and an `AudioCodec`, and `"MP3"` and `"FLAC"` are both a `Container` and an `AudioCodec`,
         * so a text matcher over the open panel is ambiguous for four of the chips.
         */
        const val ADVANCED_CONTAINER_CHIPS: String = "converter.advanced.containerChips"

        const val ADVANCED_VIDEO_CHIPS: String = "converter.advanced.videoChips"
        const val ADVANCED_AUDIO_CHIPS: String = "converter.advanced.audioChips"

        /** The error card. Rendered outside the panel, so it is reachable while collapsed. */
        const val VALIDATION_ERROR: String = "converter.validationError"

        /** One detail line of the file card, by the label it renders: `Container`, `Video`, ... */
        fun detailRow(label: String): String = "converter.fileCard.row:$label"

        /**
         * One suggested output on the validation card, by position.
         *
         * By position rather than by the text of the suggestion, because that text comes from
         * `describe`, which is itself under test -- a tag derived from it would move whenever the
         * thing it is meant to locate changed.
         */
        fun suggestion(index: Int): String = "converter.validationError.suggestion:$index"
    }

    /** `JoinScreen`. */
    object Join {
        const val CHOOSE_FILES: String = "join.chooseFiles"
        const val JOIN: String = "join.join"
        const val CHOOSE_DIFFERENT_FILES: String = "join.chooseDifferentFiles"
        const val JOIN_MORE: String = "join.joinMore"

        /** The indeterminate bar in `Joining`. */
        const val PROGRESS: String = "join.progress"

        /**
         * One picked input, by the name it displays.
         *
         * By name rather than by position, so the tag is derived from data the row already holds
         * and can stay inside `FileRow`. Passing an index down would mean the call site owned the
         * tag, and a test that supplies its own tag asserts nothing about the screen.
         */
        fun fileRow(displayName: String): String = "join.fileRow:$displayName"
    }
}
