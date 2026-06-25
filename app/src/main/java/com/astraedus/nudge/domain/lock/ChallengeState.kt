package com.astraedus.nudge.domain.lock

/**
 * UI-facing state for an in-flight Strict Mode unlock challenge.
 *
 * A ViewModel stashes one of these (plus a pending action) when a weakening method is invoked
 * while Strict Mode is on; the screen observes it to show the [com.astraedus.nudge.ui.components.ChallengeDialog].
 *
 * @param target the raw challenge string the user must type (compare via [StrictModeChallenge.verify]).
 * @param prompt short human description of what is being unlocked (e.g. "Turn off Strict Mode").
 */
data class ChallengeState(
    val target: String,
    val prompt: String
)
