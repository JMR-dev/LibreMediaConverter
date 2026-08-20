package org.libremediaconverter.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout constants shared by the top-level screens.
 *
 * Shared rather than duplicated so the two tabs cannot drift apart: an empty state that
 * looks different depending on which tab you are on reads as a bug.
 */

/** Primary actions are taller than the Material default so they read as the main affordance. */
val PrimaryButtonHeight: Dp = 56.dp

/**
 * Horizontal screen inset.
 *
 * Deliberately tighter than the vertical inset so a full-width primary button reaches
 * close to both edges of the display.
 */
val ScreenPaddingHorizontal: Dp = 16.dp

val ScreenPaddingVertical: Dp = 24.dp
