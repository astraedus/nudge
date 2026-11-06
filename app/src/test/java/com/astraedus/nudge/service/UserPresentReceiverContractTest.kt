package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5, source-level: the ONE unlock receiver and the two app-window fallbacks (spec 5.3).
 *
 * `NudgeAccessibilityService` cannot be constructed on the JVM, so the register/unregister pair and
 * the order inside the receiver are pinned over the source text, the way this codebase pins service
 * wiring. #136 and #155 each added a receiver for the same broadcasts; the merge folded them into
 * one, and this file now pins that shape: `SCREEN_ON` keeps #136's behaviour, the first-unlock stamp
 * moves on `USER_PRESENT` only, and both AppWindow fallbacks ask the same keyguard predicate.
 *
 * The failure this catches is the one #57 established as the worst in this app: a receiver
 * registered and never unregistered keeps a dead service alive, and a receiver never registered
 * silently turns the first-unlock anchor into "only the fallback ever fires".
 */
class UserPresentReceiverContractTest {

    private val serviceSource: String by lazy {
        SourceScan.code(SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt"))
    }

    /**
     * The receiver's own text, from its declaration to the end of its lazy block. A non-greedy match
     * is safe here: every nested closing brace is indented deeper than the four spaces that end the
     * block, so this stops at the receiver.
     */
    private val receiverBody: String by lazy {
        Regex("private val unlockReceiver by lazy \\{[\\s\\S]*?\\n    \\}")
            .find(serviceSource)?.value.orEmpty()
    }

    /** The USER_PRESENT branch, so a stamp in the SCREEN_ON branch cannot hide in the whole body. */
    private val userPresentBranch: String by lazy {
        val start = receiverBody.indexOf("Intent.ACTION_USER_PRESENT ->")
        if (start < 0) "" else receiverBody.substring(start)
    }

    /** The SCREEN_ON branch, from its label to the USER_PRESENT label. */
    private val screenOnBranch: String by lazy {
        val start = receiverBody.indexOf("Intent.ACTION_SCREEN_ON ->")
        val end = receiverBody.indexOf("Intent.ACTION_USER_PRESENT ->")
        if (start < 0 || end <= start) "" else receiverBody.substring(start, end)
    }

    /** The first-unlock fallback's body, same extraction rule as [receiverBody]. */
    private val fallbackBody: String by lazy {
        Regex("private fun logFirstUnlockFallback\\(packageName: String\\) \\{[\\s\\S]*?\\n    \\}")
            .find(serviceSource)?.value.orEmpty()
    }

    @Test
    fun `one receiver, registered for screen-on and unlock, beside the screen-off receiver`() {
        assertTrue("the unlock receiver must be declared", receiverBody.isNotEmpty())
        assertTrue(
            "a second receiver for the same broadcasts must not come back",
            !serviceSource.contains("userPresentReceiver")
        )
        assertTrue(
            "screen-on must keep its branch",
            receiverBody.contains("Intent.ACTION_SCREEN_ON ->")
        )
        assertTrue(
            "unlock must keep its branch",
            receiverBody.contains("Intent.ACTION_USER_PRESENT ->")
        )

        val registration = serviceSource.indexOf("registerReceiver(\n                unlockReceiver")
        assertTrue("the receiver must be registered", registration >= 0)
        val screenOffRegistration = serviceSource.indexOf(
            "registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))"
        )
        assertTrue(
            "it registers beside screen-off, in onServiceConnected",
            screenOffRegistration in 0 until registration
        )
        assertTrue(
            "the filter must carry both actions",
            serviceSource.contains("addAction(Intent.ACTION_SCREEN_ON)") &&
                serviceSource.contains("addAction(Intent.ACTION_USER_PRESENT)")
        )
    }

    @Test
    fun `exactly one registration and one unregistration`() {
        assertEquals(
            "one registration: a second one would double-handle every unlock",
            1,
            Regex("(?<!un)registerReceiver\\(\\s*unlockReceiver").findAll(serviceSource).count()
        )
        assertTrue(
            "teardown must unregister it (#57: a dead service must not be kept alive)",
            serviceSource.contains(
                "teardown.step(\"unregister_unlock_receiver\") { unregisterReceiver(unlockReceiver) }"
            )
        )
        assertEquals(
            "one unregistration",
            1,
            Regex("unregisterReceiver\\(unlockReceiver\\)").findAll(serviceSource).count()
        )
    }

    @Test
    fun `unlock serves the phone nudge and the first-unlock anchor, and only unlock stamps`() {
        assertTrue(
            "the phone nudge must still learn about the unlock",
            userPresentBranch.contains("phoneNudge.onUnlocked()")
        )
        assertTrue(
            "the evidence line describes the delivery",
            userPresentBranch.contains("logFirstUnlockEvidence(source = \"user_present\")")
        )
        assertEquals(
            "one stamp decision on USER_PRESENT",
            1,
            Regex("\\.stampIfStale\\(").findAll(userPresentBranch).count()
        )
        assertFalse(
            "a screen-on is not an unlock, so it must not move the stamp",
            screenOnBranch.contains("stampIfStale(")
        )
        assertTrue(
            "the evidence is logged before the decision",
            userPresentBranch.indexOf("logFirstUnlockEvidence") <
                userPresentBranch.indexOf("stampIfStale(")
        )
    }

    @Test
    fun `both app-window fallbacks ask the one keyguard predicate`() {
        // Review: #136's fallback and 5b's must not answer "past the keyguard" two different ways.
        val uses = Regex("isPastKeyguardNow\\(\\)").findAll(serviceSource).count()
        assertTrue(
            "each fallback calls the shared helper, at least twice plus its definition: $uses",
            uses >= 2
        )
        assertTrue(
            "the first-unlock fallback must ask it",
            fallbackBody.contains("isPastKeyguardNow()")
        )
        assertTrue(
            "the inverted exit must not come back",
            !fallbackBody.contains("isKeyguardLocked && power?.isInteractive == true")
        )
        assertTrue(
            "the helper must use the pure predicate",
            serviceSource.contains("FirstUnlockWindow.isPastKeyguard(")
        )
    }

    @Test
    fun `the fallback keeps its cheap exit before the keyguard call`() {
        val decision = fallbackBody.indexOf("FirstUnlockWindow.shouldStamp")
        val keyguard = fallbackBody.indexOf("isPastKeyguardNow()")
        val stamp = fallbackBody.indexOf("stampIfStale(")

        assertTrue("the fallback must keep the in-memory fast path", decision >= 0)
        assertTrue("the keyguard question comes after the fast path", decision < keyguard)
        assertTrue("and before the write", keyguard < stamp)
    }
}
