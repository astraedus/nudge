package com.astraedus.nudge.ui.testtags

/**
 * Stable handles for the few controls the `:qa-driver` device tests find, exposed to UiAutomator as
 * resource ids (`Modifier.semantics { testTagsAsResourceId = true }.testTag(...)` at each site).
 *
 * A tag, not the on-screen copy, because copy changes: the bash harness found "I changed my mind"
 * by its text, and a reworded button would have failed the gate for a reason that is not a bug.
 *
 * This directory holds ONLY this file, and `qa-driver/build.gradle.kts` compiles it into the driver
 * too, so the driver and the app read the same constants instead of retyping them. Keep it free
 * of Android and Compose imports: the driver has neither on that classpath.
 */
object UiTags {
    /** [com.astraedus.nudge.ui.overlay.LeaveButton]: the way out of every block screen. */
    const val LEAVE = "nudge_leave"

    /**
     * The dashboard's week card (HomeScreen), whose text carries "<n> blocked · <m> walked away".
     * On the CARD, not the summary Text: the card is clickable, which merges its children's
     * semantics into it, and a merged child's test tag is dropped (the first device run found no
     * node at all with the tag on the Text).
     */
    const val WEEK_CARD = "nudge_week_card"
}
