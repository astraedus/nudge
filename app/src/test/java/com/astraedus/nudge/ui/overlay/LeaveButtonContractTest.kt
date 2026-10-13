package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared [LeaveButton]'s WIRING. Compose UI is not JVM-testable in this repo, so, like
 * `RedirectAppContractTest` and `HoldModeContractTest`, this pins the shape of the source.
 *
 * Each assertion is a class of defect:
 *  - a block body that regrows its own small leave button instead of the shared big one (leaving
 *    must be the easiest thing on every block screen);
 *  - the leave button sliding below the redirect bubble or the daily pass, where the quieter
 *    controls would out-rank it;
 *  - the capped-width modifiers swapped into an order that silently drops the cap;
 *  - the centre visual made clickable (on HOLD it is the press-and-hold that lets the user IN).
 */
class LeaveButtonContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main source set not found from working dir ${File("").absolutePath}")

    private fun code(name: String): String {
        val file = File(sourceRoot(), "com/astraedus/nudge/ui/overlay/$name.kt")
        assertTrue("$name.kt must exist", file.exists())
        return file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    /** Body file -> the exact call it must make to the shared button. */
    private val bodies = mapOf(
        "DelayContent" to "LeaveButton(onClick = onCancel)",
        "HoldContent" to "LeaveButton(onClick = onCancel)",
        "BreathingContent" to "LeaveButton(onClick = onCancel)",
        "HardBlockContent" to """LeaveButton(text = "Go Back", onClick = onGoBack)""",
        "NukeBlockContent" to """LeaveButton(text = "Go home", onClick = onGoHome)"""
    )

    @Test
    fun `every block body renders the shared leave button wired to its walk-away`() {
        bodies.forEach { (body, call) ->
            val source = code(body)
            assertTrue("$body must call $call", source.contains(call))
            assertFalse("$body must not keep its own small OutlinedButton", source.contains("OutlinedButton("))
            assertFalse("$body must not keep its own bare Button", Regex("""\bButton\(""").containsMatchIn(source))
        }
    }

    @Test
    fun `the leave button sits above the redirect bubble and the daily pass`() {
        bodies.keys.forEach { body ->
            val source = code(body)
            val leave = source.indexOf("LeaveButton(")
            val redirect = source.indexOf("redirect()")
            assertTrue("$body: leave button before the redirect bubble", leave in 0 until redirect)
            val pass = source.indexOf("EmergencyPassAction(")
            if (pass >= 0) assertTrue("$body: leave button before the daily pass", leave < pass)
        }
    }

    @Test
    fun `the visual sits above the leave button on the timed overlays`() {
        mapOf(
            "DelayContent" to "CircularProgressIndicator(",
            "HoldContent" to "HoldTarget(",
            "BreathingContent" to "LinearProgressIndicator("
        ).forEach { (body, visual) ->
            val source = code(body)
            assertTrue(
                "$body: $visual must come before the leave button",
                source.indexOf(visual) in 0 until source.indexOf("LeaveButton(")
            )
        }
    }

    @Test
    fun `the shared button is a filled, capped, thumb-sized primary target`() {
        val source = code("LeaveButton")
        assertTrue("filled Button, not Outlined/Text", Regex("""\bButton\(""").containsMatchIn(source))
        assertFalse(source.contains("OutlinedButton"))
        assertTrue("at least 56dp tall", source.contains("heightIn(min = 56.dp)"))
        assertTrue(
            "capped at 320dp, cap applied BEFORE fillMaxWidth or it never bites",
            source.indexOf("widthIn(max = 320.dp)") in 0 until source.indexOf("fillMaxWidth()")
        )
        assertTrue("default label", source.contains("""text: String = "I changed my mind""""))
    }

    @Test
    fun `the countdown ring is never clickable`() {
        assertFalse(
            "the only click target on the delay overlay's centre is the leave button, not the ring",
            Regex("""\.clickable\(|\.combinedClickable\(""").containsMatchIn(code("DelayContent"))
        )
    }
}
