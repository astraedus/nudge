package com.astraedus.nudge.domain.events

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nudge has no `INTERNET` permission, no telemetry, and `allowBackup="false"`, and its entire
 * positioning is that nothing leaves the device. The accessibility event trace is the one thing in
 * this app that writes the user's activity to a file — and those files are COMMITTED TO A PUBLIC
 * REPOSITORY as test fixtures.
 *
 * Accessibility events carry `getText()` and `getContentDescription()`. On a `TYPE_VIEW_TEXT_CHANGED`
 * that is literally what the user is typing; on a content change it is whatever is on screen — a
 * message, a search query, a contact's name. `AccessibilityEventRecord` therefore does not have those
 * fields, deliberately, and the factory never reads them.
 *
 * That is an easy thing to undo by accident: "add the text so I can see what changed" is a reasonable
 * instinct while debugging, and the resulting leak would be invisible until a capture shipped. These
 * tests make it impossible to do quietly. They check the DATA, not just the code, because the data is
 * what actually gets published.
 */
class A11yCapturePrivacyTest {

    private fun sourceFile(relative: String): File = listOf(
        File("src/main/java/com/astraedus/nudge/$relative"),
        File("app/src/main/java/com/astraedus/nudge/$relative")
    ).firstOrNull { it.exists() } ?: error("$relative not found from ${File("").absolutePath}")

    @Test
    fun `the event record has no field that could carry user text`() {
        val fields = AccessibilityEventRecord::class.java.declaredFields.map { it.name.lowercase() }
        listOf("text", "contentdescription", "description", "title").forEach { forbidden ->
            assertFalse(
                "AccessibilityEventRecord must not carry `$forbidden` — captures are published, " +
                    "and accessibility events carry whatever the user is reading or typing",
                fields.any { it.contains(forbidden) }
            )
        }
    }

    @Test
    fun `the factory never reads text off a live event`() {
        val source = sourceFile("service/AccessibilityEventRecordFactory.kt").readText()
        listOf("event.text", "event.contentDescription", "getText()", "getContentDescription()")
            .forEach { forbidden ->
                assertFalse(
                    "AccessibilityEventRecordFactory must never read `$forbidden`",
                    source.contains(forbidden)
                )
            }
    }

    /**
     * The invariant over the shipped data itself. Every string a capture can contain must be a
     * package name, a framework/app class name, or a resource id — all of them app *structure*,
     * never app *content*. Anything else in a committed fixture is a leak that review would have to
     * catch by eye, every time, forever.
     */
    @Test
    fun `no committed capture contains anything but package names class names and resource ids`() {
        // A package name, a class name and a resource id all look like `a.b.c`, `a.b.C$D` or
        // `a.b.c:id/name`. Requiring a dot or a colon is what rejects prose: a sentence has spaces
        // and a single word has neither separator, so neither can pass.
        val structural = Regex("""^[A-Za-z][A-Za-z0-9_.$:/-]*$""")
        val enums = A11yEventType.entries.map { it.name }.toSet()

        A11yCapture.names().forEach { name ->
            A11yCapture.load(name).forEach { record ->
                listOfNotNull(
                    "packageName" to record.packageName,
                    record.className?.let { "className" to it },
                    record.sourceViewId?.let { "sourceViewId" to it }
                ).forEach { (field, value) ->
                    val separated = value.contains('.') || value.contains(':')
                    assertTrue(
                        "capture '$name' has a $field that is not a package/class/resource id: " +
                            "'$value'. Captures are published — this may be user content.",
                        value in enums || (separated && structural.matches(value))
                    )
                }
            }
        }
    }

    /**
     * The capture script must not widen what it keeps. It extracts only the trace's own `EV {...}`
     * payload; a change to grep whole logcat lines would sweep in every other tag's output, which on
     * this device includes app logs we have no business publishing.
     */
    @Test
    fun `the capture script extracts only the structured trace payload`() {
        val script = listOf(File("../scripts/a11y-capture.sh"), File("scripts/a11y-capture.sh"))
            .firstOrNull { it.exists() }
            ?: error("a11y-capture.sh not found from ${File("").absolutePath}")
        val text = script.readText()
        assertTrue(
            "the script must filter logcat to the trace tag alone",
            text.contains("logcat -s \"NudgeA11yTrace:I\"")
        )
        assertTrue(
            "the script must keep only the EV payload, not whole log lines",
            text.contains("""s/^.*\bEV \({.*}\)$/\1/p""")
        )
    }
}
