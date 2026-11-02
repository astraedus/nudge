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

    /**
     * The receiver's own text, from its declaration to the end of its lazy block. A non-greedy
     * match is safe here: every nested closing brace is indented deeper than the four spaces that
     * end the block, so this stops at the receiver and does not spill into the fallback below.
     */
    private val receiverBody: String by lazy {
        Regex("private val userPresentReceiver by lazy \\{[\\s\\S]*?\\n    \\}")
            .find(serviceSource)?.value.orEmpty()
    }

    /** The fallback's body, same extraction rule as [receiverBody]. */
    private val fallbackBody: String by lazy {
        Regex("private fun logFirstUnlockFallback\\(packageName: String\\) \\{[\\s\\S]*?\\n    \\}")
            .find(serviceSource)?.value.orEmpty()
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
    fun `the receiver logs its evidence before deciding`() {
        // PR 5a's spike assertion (log only, change nothing) is deliberately gone: PR 5b made this
        // receiver the anchor. What survives is the ordering that kept the spike readable — the
        // evidence line describes the delivery whether or not it stamps.
        val evidence = receiverBody.indexOf("logFirstUnlockEvidence(source = \"user_present\")")
        val decision = receiverBody.indexOf("stampIfStale(")

        assertTrue("the delivery must still be logged", evidence >= 0)
        assertTrue("the evidence must be logged before the stamp decision", evidence < decision)
        assertTrue(
            "the receiver must not evaluate a block",
            !receiverBody.contains("evaluateBlockUseCase")
        )
    }

    @Test
    fun `both sites stamp through the cache's persisted-anchor decision`() {
        listOf("receiver" to receiverBody, "fallback" to fallbackBody).forEach { (label, body) ->
            assertTrue("the $label body must be findable", body.isNotEmpty())
            assertTrue(
                "the $label must ask the cache to decide (spec 5.3)",
                body.contains(".stampIfStale(")
            )
            assertEquals(
                "one stampIfStale call in the $label: a second could double-stamp",
                1,
                Regex("\\.stampIfStale\\(").findAll(body).count()
            )
            assertTrue(
                "the $label must not write the anchor itself: the decision needs the persisted value",
                !body.contains("record(")
            )
        }
    }

    @Test
    fun `the fallback keeps its cheap exit before the keyguard call`() {
        // Device QA found the cold-cache bug this guards: the in-memory stamp reads 0 for the first
        // moments of a process. The cheap check must stay (it keeps the binder call off app
        // switches), and the cache must be what writes, against the persisted value.
        val decision = fallbackBody.indexOf("FirstUnlockWindow.shouldStamp")
        val keyguard = fallbackBody.indexOf("KeyguardManager::class.java")
        val stamp = fallbackBody.indexOf("stampIfStale(")

        assertTrue("the fallback must keep the in-memory fast path", decision >= 0)
        assertTrue("the binding decision must come after the fast path", decision < stamp)
        assertTrue("the keyguard check stays out of the hot path's way", keyguard in 0 until stamp)
    }
}
