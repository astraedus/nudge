package com.astraedus.nudge.service

import com.astraedus.nudge.domain.health.ProtectionFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every fault has copy, and the copy names the RIGHT recovery.
 *
 * This is the one alert the debug trigger could not make fire on the bench.
 * `ACCESSIBILITY_CRASHED` needs the system to be holding our service in the granted-but-unbound
 * state at the instant the check evaluates, and on an idle Pixel 3 the rebind completes in
 * 150ms-3s, which is faster than an `am broadcast` round trip. Device QA proved the state exists
 * (repeatedly, via `dumpsys accessibility`) and proved the posting pipeline works (the sibling
 * `ACCESSIBILITY_DISABLED` and `MONITOR_SERVICE_DEAD` faults both posted real notifications on
 * `nudge_protection_alerts` through the identical `notify()` body) - but never got the two to
 * coincide. So the remaining risk is not "does it post", it is "does it post the right words".
 *
 * That risk is worth a test on its own merits. The crashed user's Accessibility switch already
 * reads ON. Telling them their permission is off would send them looking for a toggle that is
 * already where the message says to put it, and telling them to "turn it back on" is worse than
 * useless - it is the instruction that makes them disable a service which, per AOSP, only a
 * deliberate off-and-on can revive. Wrong copy here costs the user the fix.
 *
 * Iterating the real enum rather than a hardcoded list is the point: a fault added later fails
 * this until someone writes its copy, instead of silently falling through to nothing.
 */
class ProtectionAlertCopyTest {

    /** Fault to string-resource stem. Deliberately explicit - the names are not derivable. */
    private val resourceStems = mapOf(
        ProtectionFault.ACCESSIBILITY_DISABLED to "protection_alert_accessibility",
        ProtectionFault.ACCESSIBILITY_CRASHED to "protection_alert_crashed",
        ProtectionFault.MONITOR_SERVICE_DEAD to "protection_alert_service"
    )

    private val strings: String by lazy {
        listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("strings.xml not found from ${File("").absolutePath}")
    }

    private fun string(name: String): String {
        val match = Regex("""<string name="$name">([\s\S]*?)</string>""").find(strings)
            ?: error("string resource $name is missing")
        return match.groupValues[1].replace("\\'", "'").trim()
    }

    private fun notifierSource(): String =
        listOf(
            File("src/main/java/com/astraedus/nudge/service/ProtectionAlertNotifier.kt"),
            File("app/src/main/java/com/astraedus/nudge/service/ProtectionAlertNotifier.kt")
        ).first { it.exists() }.readText()

    @Test
    fun `every fault the watchdog can report has copy wired to it`() {
        assertEquals(
            "Every ProtectionFault needs a title/body pair. A fault with no entry here would " +
                "reach ProtectionAlertNotifier's `when` with nothing to say.",
            ProtectionFault.values().toSet(),
            resourceStems.keys
        )

        val source = notifierSource()
        ProtectionFault.values().forEach { fault ->
            assertTrue(
                "ProtectionAlertNotifier must handle ${fault.name}",
                source.contains(fault.name)
            )
            val stem = resourceStems.getValue(fault)
            listOf("${stem}_title", "${stem}_body").forEach { resource ->
                assertTrue(
                    "$resource must not be blank - ${fault.name} would post an empty notification",
                    string(resource).isNotBlank()
                )
                assertTrue(
                    "ProtectionAlertNotifier must reference R.string.$resource for ${fault.name}",
                    source.contains(resource)
                )
            }
        }
    }

    /**
     * The failure this exists for: a user whose switch reads ON being told to turn it on. The two
     * faults look identical to them and need opposite instructions, so the copy must not converge.
     */
    @Test
    fun `the crashed alert names off-and-on recovery, never the grant prompt`() {
        val crashed = string("protection_alert_crashed_body").lowercase()
        val disabled = string("protection_alert_accessibility_body").lowercase()

        assertTrue(
            "The crashed body must tell the user to toggle it off and back on (or restart) - " +
                "AOSP's crashed set is cleared by a user toggle, an update, an uninstall or a " +
                "force-stop, and by nothing else. That instruction IS the fix.",
            (crashed.contains("off and back on") || crashed.contains("off and on")) ||
                crashed.contains("restart")
        )
        assertFalse(
            "The crashed body must not claim the permission is off - it is ON, which is exactly " +
                "what makes this state invisible to the user and this sentence a dead end.",
            crashed.contains("permission is off") || crashed.contains("is not enabled")
        )
        assertTrue(
            "The disabled body must tell the user to turn it back on - for THIS fault the switch " +
                "really is off and that really is the fix.",
            disabled.contains("turn it back on") || disabled.contains("turn it on")
        )
        assertFalse(
            "The two bodies must not be identical - they are separate faults precisely because " +
                "they need opposite instructions",
            crashed == disabled
        )
    }
}
