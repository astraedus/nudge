package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * How every block body sits in its window: CENTRED while it fits, SCROLLABLE only when it does not.
 *
 * Every overlay is a centred column, and before the redirect-app bubble most of them already came
 * within a few dp of a Pixel 3's usable height once a daily-limit line, the daily pass and a rule
 * footer were all showing; on a short Android 8 screen they clipped top and bottom, with the
 * "I changed my mind" button among the casualties. The bubble adds roughly a hundred dp to each.
 *
 * `wrapContentHeight` lets the scroll container shrink to its content and centres it; when the
 * content is taller than the window the container takes the window's height and scrolls.
 *
 * Scrolling is ENABLED only when there is somewhere to scroll ([ScrollState.maxValue] > 0). That is
 * not tidiness: an enabled scroll container claims a vertical drag past touch slop even when it
 * cannot move, and on a HOLD a thumb that drifts a few pixels would have its press cancelled by a
 * scroll that goes nowhere. On a screen where the overlay fits, the hold sees exactly the input it
 * saw before this existed.
 */
internal fun Modifier.overlayContentScroll(scroll: ScrollState): Modifier =
    this
        .wrapContentHeight(Alignment.CenterVertically)
        .verticalScroll(scroll, enabled = scroll.maxValue > 0)
