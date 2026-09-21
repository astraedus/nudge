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
}

/** One line under the picker saying what the selected mode actually does. */
internal fun blockModeDescription(mode: BlockMode): String = when (mode) {
    BlockMode.NONE -> "Not blocked."
    BlockMode.HARD_BLOCK -> "Completely blocks the app. You can only go back to the home screen."
    BlockMode.DELAY -> "Shows a countdown timer before letting you in. Gives you time to reconsider."
    BlockMode.HOLD -> "You hold a button for the whole wait. Let go and it starts over."
    BlockMode.BREATHING -> "Guides you through a breathing exercise before opening. Calms the impulse."
}
