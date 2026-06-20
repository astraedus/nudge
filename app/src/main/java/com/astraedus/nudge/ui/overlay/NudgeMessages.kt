package com.astraedus.nudge.ui.overlay

object NudgeMessages {
    val delayTitles = listOf(
        "Take a moment to think...",
        "Pause and reflect",
        "Is this intentional?",
        "Before you scroll...",
        "A moment of awareness",
    )

    val delaySubtitles = listOf(
        "Do you really need to open this app right now?",
        "What were you about to do instead?",
        "Will this bring you closer to your goals?",
        "You chose to add friction here for a reason.",
        "This is your future self thanking you.",
    )

    val hardBlockMessages = listOf(
        "You've blocked access to this app",
        "This app is off-limits right now",
        "Your future self will thank you",
        "Time to do something else",
        "You set this boundary for a reason",
    )

    val allMessages: List<String>
        get() = delayTitles + delaySubtitles + hardBlockMessages

    fun getRandom(): String = allMessages.random()

    /**
     * Resolves the pool of messages to display, given the user's custom multiline text
     * and the built-in default list.
     *
     * The custom text is one message per line; lines are trimmed and blanks dropped.
     * If the result is empty (null/blank/whitespace-only custom text), falls back to
     * [default]. Pure (no Android imports) so it is JVM-unit-testable.
     */
    fun resolvePool(customRaw: String?, default: List<String>): List<String> {
        val lines = customRaw?.split("\n")?.map(String::trim)?.filter(String::isNotBlank) ?: emptyList()
        return lines.ifEmpty { default }
    }
}
