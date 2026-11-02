package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5, source-level: the PR 5a spike's wiring contract (spec 5.3).
 *
 * `NudgeAccessibilityService` cannot be constructed on the JVM (`AccessibilityService`, a real
 * `Context`, Hilt), so the register/unregister pair is pinned where the rest of this codebase pins
 * service wiring: over the source text. The behaviour the receiver is supposed to have — nothing —
 * is pinned by asserting its body logs and touches no preference.
 *
 * The failure this catches is the one #57 established as the worst in this app: a receiver
 * registered and never unregistered keeps a dead service alive, and a receiver never registered
 * silently turns the first-unlock anchor into "only the fallback ever fires".
 */
class UserPresentReceiverContractTest {

    private val srcDir: File by lazy {
        listOf(File("app/src"), File("src")).firstOrNull { File(it, "main").isDirectory }
            ?: error("app/src not found from ${File("").absolutePath}")
    }

    private val serviceSource: String by lazy {
        File(srcDir, "main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt").readText()
    }

    @Test
    fun `the receiver is declared, filtered on USER_PRESENT, and registered next to screen-off`() {
        assertTrue(
            "the USER_PRESENT receiver must be declared",
            serviceSource.contains("private val userPresentReceiver by lazy {")
        )
        assertTrue(
            "the receiver must ignore every other action",
            serviceSource.contains("if (intent?.action != Intent.ACTION_USER_PRESENT) return")
        )
        assertTrue(
            "the receiver must be registered with ACTION_USER_PRESENT",
            serviceSource.contains("android.content.IntentFilter(Intent.ACTION_USER_PRESENT)")
        )

        val registration = serviceSource.indexOf("registerReceiver(\n                userPresentReceiver")
        assertTrue("the receiver must be registered", registration >= 0)
        val screenOffRegistration = serviceSource.indexOf(
            "registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))"
        )
        assertTrue(
            "the spike registers beside screen-off, in onServiceConnected",
            screenOffRegistration in 0 until registration
        )
    }

    @Test
    fun `the receiver is unregistered in teardown`() {
        assertTrue(
            "teardown must unregister the receiver (#57: a dead service must not be kept alive)",
            serviceSource.contains(
                "teardown.step(\"unregister_user_present_receiver\") " +
                    "{ unregisterReceiver(userPresentReceiver) }"
            )
        )
    }

    @Test
    fun `exactly one registration and one unregistration`() {
        assertEquals(
            "one registration: a second one would double-log every unlock",
            1,
            Regex("(?<!un)registerReceiver\\(\\s*userPresentReceiver").findAll(serviceSource).count()
        )
        assertEquals(
            "one unregistration",
            1,
            Regex("unregisterReceiver\\(userPresentReceiver\\)").findAll(serviceSource).count()
        )
    }

    @Test
    fun `the spike logs and changes no behaviour`() {
        val start = serviceSource.indexOf("private val userPresentReceiver by lazy {")
        assertTrue(start >= 0)
        val body = serviceSource.substring(start, start + 1_200)

        assertTrue(
            "the receiver's only effect is an evidence log",
            body.contains("logFirstUnlockEvidence(source = \"user_present\")")
        )
        assertTrue(
            "PR 5a must not read or write preferences",
            !body.contains("nudgePreferences")
        )
        assertTrue(
            "PR 5a must not evaluate a block",
            !body.contains("evaluateBlockUseCase")
        )
    }
}
