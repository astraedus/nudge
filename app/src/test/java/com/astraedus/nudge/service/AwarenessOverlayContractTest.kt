package com.astraedus.nudge.service

import com.astraedus.nudge.NudgeIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on the awareness overlays' ACCESSIBILITY IDENTITY
 * ([#41](https://github.com/astraedus/nudge/issues/41)).
 *
 * ## Why a test that reads source code
 *
 * The fix for #41 is that the interaction counter and the time-remaining pill say what they are in
 * their accessibility class name, so `EventClassifier` can tell "Nudge drew over the app you are in"
 * from "Nudge is in front of you". Every value-level test of that fix is written against a class
 * name, so every one of them would go on passing if a future edit built the views with a bare
 * `TextView(ctx)` again: the classifier would be asked about `android.widget.TextView`, answer
 * `OwnUi`, move the foreground to Nudge, and a user past their daily limit would stop being blocked
 * — silently, with a green suite. The defect lives in HOW the view is constructed, which is exactly
 * the kind of thing `BlockOverlayLaunchContractTest` and `EventDispatchOrderContractTest` already
 * read source to pin.
 *
 * The rule is DISCOVERED, not listed: any framework widget constructed in either manager fails,
 * including one nobody has added yet. A hand-written list of the three TextViews that exist today
 * would pin yesterday's bug.
 */
class AwarenessOverlayContractTest {

    private val managerFiles = listOf(
        "main/java/com/astraedus/nudge/service/CounterOverlayManager.kt",
        "main/java/com/astraedus/nudge/service/TimeRemainingOverlayManager.kt",
        // The tab-vanish cover. Same rule, sharper consequence: this window is drawn OVER the app it
        // is enforcing against, so a bare widget here would move the foreground to Nudge and drop
        // every subsequent block for that exact app — the cover would silently disable blocking for
        // the app it covers.
        "main/java/com/astraedus/nudge/service/TabCoverOverlayManager.kt"
    )

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    /**
     * The one that matters. `new TextView(...)` anywhere in an awareness overlay is a window that
     * reports a framework class name, and a framework class name is not an identity.
     */
    @Test
    fun `awareness overlays are built only from views that carry the identity`() {
        val bareWidget = Regex("""(?<!AwarenessOverlayWindow\.)\b(TextView|LinearLayout|FrameLayout|RelativeLayout|ImageView|View)\s*\(\s*ctx""")
        managerFiles.forEach { relative ->
            val code = stripComments(read(relative))
            val offenders = bareWidget.findAll(code).map { it.groupValues[1] }.toList()
            assertTrue(
                "$relative constructs ${offenders.distinct()} directly. Every view in an awareness " +
                    "overlay must be an AwarenessOverlayWindow type, or its window and content " +
                    "events report a framework class name and the classifier reads them as OwnUi " +
                    "again (issue #41)",
                offenders.isEmpty()
            )
            assertTrue(
                "$relative must build its views from AwarenessOverlayWindow",
                code.contains("AwarenessOverlayWindow.")
            )
        }
    }

    /**
     * The identity must be DERIVED. A literal would be free to drift from the class it names, which
     * is the shape that left `shouldClearForOwnPackageEvent` dead for months (issue #33).
     */
    @Test
    fun `the identity is derived from a real class, not written out`() {
        val source = stripComments(read("main/java/com/astraedus/nudge/service/AwarenessOverlayWindow.kt"))
        assertTrue(
            "CLASS_NAME must come from a real class object, never a string literal",
            source.contains("AwarenessOverlayWindow::class.java.name")
        )
        assertTrue(
            "the identity must sit inside this app's class namespace (${NudgeIdentity.CLASS_NAMESPACE}), " +
                "like every real class name an accessibility event can carry, got " +
                AwarenessOverlayWindow.CLASS_NAME,
            AwarenessOverlayWindow.CLASS_NAME.startsWith(NudgeIdentity.CLASS_NAMESPACE + ".")
        )
        assertTrue(
            "every awareness-overlay view type must answer the identity",
            AwarenessOverlayWindow.CLASS_NAMES.contains(AwarenessOverlayWindow.CLASS_NAME)
        )
    }

    /**
     * Every view type declared in the file must override `getAccessibilityClassName`, or the ones
     * that forgot report their framework name and are invisible to the classifier — the half-fixed
     * state that would leave the counter's own text updates classified as `OwnUi`.
     */
    @Test
    fun `every view type in the identity file overrides its accessibility class name`() {
        val source = stripComments(read("main/java/com/astraedus/nudge/service/AwarenessOverlayWindow.kt"))
        val declared = Regex("""class (\w+)\(context: Context\)""").findAll(source)
            .map { it.groupValues[1] }.toList()
        assertTrue("the identity file must declare at least one view type", declared.isNotEmpty())
        val overrides = Regex("""override fun getAccessibilityClassName\(\): CharSequence = CLASS_NAME""")
            .findAll(source).count()
        assertEquals(
            "each of $declared must override getAccessibilityClassName to return CLASS_NAME",
            declared.size,
            overrides
        )
    }
}
