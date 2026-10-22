package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #71: a block screen shrunk into a floating window or a split-screen half leaves the
 * blocked app usable beside or behind it. Every full-screen Nudge screen that stands between the
 * user and something (the block overlay, the Strict Mode guard, the PiP explainer) must refuse
 * multi-window and picture-in-picture in the manifest.
 *
 * The CLASS, not the instance: every activity on `Theme.Nudge.Overlay`, so a new overlay screen
 * cannot ship without the flags. The manifest is the only place this lives; nothing at runtime
 * can observe a missing flag until a user on the right phone finds the gesture.
 */
class OverlayManifestContractTest {

    private val manifest: String by lazy {
        listOf(File("app/src/main/AndroidManifest.xml"), File("src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("AndroidManifest.xml not found from ${File("").absolutePath}")
    }

    private val overlayActivities: List<String> by lazy {
        Regex("""<activity\b[^>]*?/?>""").findAll(manifest)
            .map { it.value }
            .filter { it.contains("""android:theme="@style/Theme.Nudge.Overlay"""") }
            .toList()
    }

    private fun nameOf(block: String): String =
        Regex("""android:name="([^"]+)"""").find(block)?.groupValues?.get(1) ?: block

    @Test
    fun `the overlay activities are found, including the block screen`() {
        val names = overlayActivities.map(::nameOf)
        assertTrue(
            "expected the block overlay among the Theme.Nudge.Overlay activities, found $names",
            ".ui.overlay.BlockOverlayActivity" in names
        )
        assertTrue("expected at least 3 overlay activities, found $names", names.size >= 3)
    }

    @Test
    fun `every overlay activity refuses multi-window`() {
        val missing = overlayActivities
            .filterNot { it.contains("""android:resizeableActivity="false"""") }
            .map(::nameOf)
        assertTrue("overlay activities without resizeableActivity=false: $missing", missing.isEmpty())
    }

    @Test
    fun `every overlay activity refuses picture-in-picture`() {
        val missing = overlayActivities
            .filterNot { it.contains("""android:supportsPictureInPicture="false"""") }
            .map(::nameOf)
        assertTrue("overlay activities without supportsPictureInPicture=false: $missing", missing.isEmpty())
    }
}
