package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.block.BlockLaunchGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app has TWO identities and they are not interchangeable. This is what keeps anyone from
 * collapsing them again ([#33](https://github.com/astraedus/nudge/issues/33)).
 *
 * `applicationId = dev.astraedus.nudge` is what an accessibility event carries as its package.
 * `namespace = com.astraedus.nudge` is what it carries as the prefix of a class name. For months
 * `NudgeAccessibilityService.shouldClearForOwnPackageEvent` asked `className.startsWith(applicationId)`
 * and therefore answered false for every event this app can emit: the `own_app_window` branch ran on
 * no device, ever, and a counter or time-remaining overlay could linger over Nudge's own screens.
 *
 * **Nothing failed.** Its three unit tests built the predicate's own constant out of the literal
 * `"com.astraedus.nudge"` and fed it the same literal back, so the production value was never in the
 * room. That is the trap `tasks/lessons.md` (2026-09-14) records in general form, and the reason the
 * assertions here are all against values DERIVED from the real build:
 *
 *  - [NudgeIdentity] reads both identities off `BuildConfig` instead of quoting them;
 *  - the two must be different strings, which is the fact the old code denied;
 *  - both the service's runtime namespace and the pure gate's literal must equal the real one;
 *  - the predicate must answer yes for real class names and no for the applicationId — i.e. the
 *    historic mistake is now a red test, not a silently dead branch.
 */
class OwnClassNamespaceContractTest {

    /** Real class names this app really does emit in `AccessibilityEvent.getClassName()`. */
    private val realOwnClassNames = listOf(
        BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS,
        "com.astraedus.nudge.ui.overlay.BlockOverlayActivity",
        AwarenessOverlayWindow.CLASS_NAME
    )

    private val appBuildGradle: String by lazy {
        val candidates = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
        (candidates.firstOrNull { it.exists() }
            ?: error("app/build.gradle.kts not found from ${File("").absolutePath}"))
            .readText()
    }

    private fun buildValue(key: String): String =
        Regex("""$key\s*=\s*"([^"]+)"""").find(appBuildGradle)?.groupValues?.get(1)
            ?: error("$key not found in app/build.gradle.kts")

    @Test
    fun `the applicationId and the class namespace are different strings`() {
        assertNotEquals(
            "if these are ever equal, every predicate that confuses them starts passing by " +
                "accident and issue #33 becomes invisible again",
            NudgeIdentity.APPLICATION_ID,
            NudgeIdentity.CLASS_NAMESPACE
        )
    }

    @Test
    fun `the test identities are the ones the build file declares`() {
        assertEquals(
            "NudgeIdentity.APPLICATION_ID must be the applicationId the app actually ships with",
            buildValue("applicationId"),
            NudgeIdentity.APPLICATION_ID
        )
        assertEquals(
            "NudgeIdentity.CLASS_NAMESPACE must be the namespace the app's classes actually live in",
            buildValue("namespace"),
            NudgeIdentity.CLASS_NAMESPACE
        )
    }

    @Test
    fun `production derives the class namespace, and the pure gate's literal agrees with it`() {
        assertEquals(
            "the service must test class names against the NAMESPACE, derived from a real class",
            NudgeIdentity.CLASS_NAMESPACE,
            NudgeAccessibilityService.OWN_CLASS_NAMESPACE
        )
        assertEquals(
            "BlockLaunchGate is pure Kotlin so its namespace is a literal; this is the assertion " +
                "that keeps the literal honest",
            NudgeIdentity.CLASS_NAMESPACE,
            BlockLaunchGate.OWN_CLASS_NAMESPACE
        )
    }

    @Test
    fun `every class name this app emits is recognised as ours`() {
        realOwnClassNames.forEach { className ->
            assertTrue(
                "$className is a class this app ships and must be recognised as ours",
                BlockLaunchGate.isOwnNudgeClass(className, NudgeIdentity.CLASS_NAMESPACE)
            )
        }
    }

    @Test
    fun `another app's window is not ours, and neither is a framework widget`() {
        listOf(
            "com.instagram.android.MainTabActivity",
            "android.widget.FrameLayout",
            "com.astraedusnudge.Imposter",
            null
        ).forEach { className ->
            assertFalse(
                "$className must not be read as one of our classes",
                BlockLaunchGate.isOwnNudgeClass(className, NudgeIdentity.CLASS_NAMESPACE)
            )
        }
    }

    @Test
    fun `the own-window clear fires for a real Nudge window and not for another app's`() {
        assertTrue(
            "this is the branch that was dead in production: a real Nudge window must clear the " +
                "awareness overlays",
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS,
                ownClassNamespace = NudgeAccessibilityService.OWN_CLASS_NAMESPACE
            )
        )
        assertFalse(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.instagram.android.MainTabActivity",
                ownClassNamespace = NudgeAccessibilityService.OWN_CLASS_NAMESPACE
            )
        )
    }

    /**
     * The counterfactual, and the whole reason this file exists: passing the APPLICATION ID where
     * the namespace belongs makes the predicate unable to fire. That was production for months.
     */
    @Test
    fun `comparing a class name against the applicationId can never match, which was the bug`() {
        realOwnClassNames.forEach { className ->
            assertFalse(
                "$className does not start with ${NudgeIdentity.APPLICATION_ID} -- this is issue " +
                    "#33 reproduced, and it must stay a test rather than a shipped branch",
                NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                    eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    className = className,
                    ownClassNamespace = NudgeIdentity.APPLICATION_ID
                )
            )
        }
    }

    /**
     * The awareness overlays live inside our own namespace, so the predicate above says yes to
     * them. They must never REACH it: they are classified `AwarenessOverlay` and return before the
     * `OwnUi` branch, or a pill would order itself hidden the moment it appeared (issue #41).
     */
    @Test
    fun `the awareness overlay identity is one of our classes, and is excluded earlier`() {
        assertTrue(
            BlockLaunchGate.isOwnNudgeClass(
                AwarenessOverlayWindow.CLASS_NAME,
                NudgeIdentity.CLASS_NAMESPACE
            )
        )
        val dispatch = File("app/src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt")
            .takeIf { it.exists() }
            ?: File("src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt")
        val source = dispatch.readText()
        val awareness = source.indexOf("if (signal is ForegroundSignal.AwarenessOverlay)")
        val ownUi = source.indexOf("if (signal is ForegroundSignal.OwnUi)")
        assertTrue("both branches must exist", awareness > 0 && ownUi > 0)
        assertTrue(
            "the awareness-overlay return must come BEFORE the OwnUi branch, or the counter's own " +
                "window event reaches the clear that hides the counter",
            awareness < ownUi
        )
    }
}
