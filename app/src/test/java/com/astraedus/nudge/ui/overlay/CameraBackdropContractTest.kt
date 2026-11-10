package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape checks for "camera on block screens" that the compiler cannot see (source scans through
 * [SourceScan], per the repo rule). Each names the failure it guards:
 *
 *  - a screen keeping an opaque background would hide the camera behind it (the setting "does
 *    nothing" on that screen), or a screen hard-coding Transparent would show the app behind it
 *    with the setting off;
 *  - the backdrop outside PauseWhile would keep the camera open under the redirect picker;
 *  - outside `key(blockToken)` a re-delivered block would inherit the last block's camera failure;
 *  - an `unbindAll` would close another app's camera session, and any use case besides Preview
 *    would mean frames go somewhere other than the screen.
 */
class CameraBackdropContractTest {

    private fun code(relative: String): String =
        SourceScan.code(SourceScan.kotlinFile("com/astraedus/nudge/ui/overlay/$relative"))

    private val activity by lazy { code("BlockOverlayActivity.kt") }

    /** The bodies the activity renders, from its `when (mode)` (same derivation as RedirectAppContractTest). */
    private val bodies: List<String> by lazy {
        val whenBlock = activity.substringAfter("when (mode) {").substringBefore("RedirectAppPickerHost(")
        Regex("""\b([A-Z]\w*Content)\(""").findAll(whenBlock).map { it.groupValues[1] }.distinct().toList()
    }

    @Test
    fun `every body but Mirror draws its own background through overlaySurfaceColor`() {
        assertTrue("derivation found the bodies", bodies.size >= 6)
        (bodies - "MirrorContent").forEach { body ->
            val source = code("$body.kt")
            assertTrue("$body must colour its Surface with overlaySurfaceColor()", source.contains("color = overlaySurfaceColor()"))
            assertFalse(
                "$body must not paint the theme background itself, or the camera is hidden",
                source.contains("color = MaterialTheme.colorScheme.background")
            )
        }
    }

    @Test
    fun `the backdrop wraps every body inside the pause and the per-delivery key`() {
        val keyed = activity.substringAfter("key(blockToken) {")
        val paused = keyed.substringAfter("PauseWhile(paused = redirect.pickerOpen) {")
        assertTrue("CameraBackdrop must sit inside PauseWhile", paused.substringBefore("when (mode) {").contains("CameraBackdrop("))
        val wrapped = paused.substringAfter("CameraBackdrop(").substringBefore("RedirectAppPickerHost(redirect)")
        bodies.forEach { assertTrue("$it must be drawn inside the backdrop", wrapped.contains("$it(")) }
        assertEquals("one backdrop", 1, Regex("""\bCameraBackdrop\(""").findAll(activity).count())
    }

    @Test
    fun `the backdrop is told the screen the activity is actually rendering`() {
        assertTrue(activity.contains("screen = OverlayScreen.of(mode, nuke)"))
        assertTrue(activity.contains("settingOn = cameraOnBlockScreens"))
    }

    @Test
    fun `the block screens open the camera from one place, for Preview only, and never unbindAll`() {
        val overlay = SourceScan.kotlinSources("com/astraedus/nudge/ui/overlay")
        val binders = overlay.filter { SourceScan.code(it).contains("bindToLifecycle(") }.map { it.name }
        assertEquals(listOf("FrontCameraPreview.kt"), binders)

        val preview = code("FrontCameraPreview.kt")
        assertTrue(preview.contains("bindToLifecycle(cameraOwner, selector, preview)"))
        assertTrue("dispose unbinds our use case", preview.contains("provider?.unbind(preview)"))
        listOf("ImageCapture", "ImageAnalysis", "VideoCapture", "Recorder").forEach {
            assertFalse("FrontCameraPreview must not use $it", preview.contains(it))
        }
        // Scoped to the block screens: the QR scanner owns its own activity and its own camera.
        overlay.forEach {
            assertFalse("${it.name} must never unbindAll", SourceScan.code(it).contains("unbindAll"))
        }
    }

    @Test
    fun `the camera is only open while the screen is resumed`() {
        val preview = code("FrontCameraPreview.kt")
        assertTrue(preview.contains("GatedLifecycleOwner(parent, ::mirrorCameraLifecycleState)"))
    }
}
