package com.astraedus.nudge.ui.components

import com.astraedus.nudge.domain.model.BlockMode

/**
 * The words the app puts on a [BlockMode], in ONE place.
 *
 * Two screens offer the same mode picker — the rule editor and the unified app config — and they
 * each carried their own `when` over every mode, so the same enum could be called two different
 * things depending on which screen the user reached it from. Worse, a new mode had to be spelled
 * out twice before it was fully visible, and the compiler only told you about the copy you
 * remembered to look at.
 *
 * Both functions are exhaustive `when`s with no `else`, so adding a mode to [BlockMode] does not
 * compile until it has been given a name and a sentence.
 */
internal fun blockModeLabel(mode: BlockMode): String = when (mode) {
    BlockMode.NONE -> "Off"
    BlockMode.HARD_BLOCK -> "Hard Block"
    BlockMode.DELAY -> "Delay"
    BlockMode.HOLD -> "Hold"
    BlockMode.BREATHING -> "Breathing"
    BlockMode.MIRROR -> "Mirror"
}

/**
 * The label inside a compact picker, where each option gets a fraction of the screen width.
 * One word each: "Hard Block" in a quarter-width segment with `maxLines = 1` was clipped to "Hard"
 * and, overflowing, drawn off-centre (device QA, groups PR 2).
 */
internal fun blockModeShortLabel(mode: BlockMode): String = when (mode) {
    BlockMode.HARD_BLOCK -> "Hard"
    BlockMode.NONE, BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING, BlockMode.MIRROR ->
        blockModeLabel(mode)
}

/**
 * One line under the picker saying what the selected mode actually does. [manyApps] is true where
 * the rule covers several apps (a group, an All apps rule): Hard Block then reads "these apps", not
 * "the app". Required, so every picker states which it is (device QA screenshot review, PR 4).
 */
internal fun blockModeDescription(mode: BlockMode, manyApps: Boolean): String = when (mode) {
    BlockMode.NONE -> "Not blocked."
    BlockMode.HARD_BLOCK ->
        "Completely blocks ${if (manyApps) "these apps" else "the app"}. You can only go back to the home screen."
    BlockMode.DELAY -> "Shows a countdown timer before letting you in. Gives you time to reconsider."
    BlockMode.HOLD -> "You hold a button for the whole wait. Let go and it starts over."
    BlockMode.BREATHING -> "Guides you through a breathing exercise before opening. Calms the impulse."
    BlockMode.MIRROR ->
        "Shows your front camera for the wait, before ${if (manyApps) "they open" else "the app opens"}."
}
