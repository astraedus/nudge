package com.astraedus.nudge.ui.overlay

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MIRROR decisions, pure (layer L3): which body a block shows, which camera states fall back,
 * and the lifecycle state that decides when the camera is open. The rule under test is "the camera
 * is a picture, never a gate": every path that is not a working camera is the DELAY body, so a
 * camera problem can only ever cost the user the picture, never the wait.
 */
class MirrorFallbackTest {

    @Test
    fun `the camera body only when permission, a front camera and no failure all hold`() {
        val bools = listOf(true, false)
        for (granted in bools) for (front in bools) for (failed in bools) {
            val expected = if (granted && front && !failed) MirrorBody.CAMERA else MirrorBody.DELAY
            assertEquals(
                "granted=$granted front=$front failed=$failed",
                expected,
                MirrorFallback.bodyFor(permissionGranted = granted, hasFrontCamera = front, cameraFailed = failed)
            )
        }
    }

    @Test
    fun `no permission, no front camera, or a failure each give the countdown body on their own`() {
        assertEquals(MirrorBody.DELAY, MirrorFallback.bodyFor(false, true, false))
        assertEquals(MirrorBody.DELAY, MirrorFallback.bodyFor(true, false, false))
        assertEquals(MirrorBody.DELAY, MirrorFallback.bodyFor(true, true, true))
    }

    /**
     * Every CameraX error code falls back, the "recoverable" in-use ones included. The literal
     * codes are CameraState.ERROR_* (1..7 in CameraX 1.5); the function takes any non-null code,
     * so a code added in a later CameraX also falls back rather than leaving a black screen.
     */
    @Test
    fun `any camera error falls back and no error does not`() {
        assertFalse(MirrorFallback.isFailure(null))
        (listOf(Int.MIN_VALUE, -1, 0) + (1..7) + listOf(99, Int.MAX_VALUE)).forEach { code ->
            assertTrue("error code $code must fall back", MirrorFallback.isFailure(code))
        }
    }

    @Test
    fun `the camera is open only while the block is resumed and the countdown is running`() {
        Lifecycle.State.entries.forEach { parent ->
            listOf(true, false).forEach { running ->
                val state = mirrorCameraLifecycleState(parent, running)
                val open = state.isAtLeast(Lifecycle.State.STARTED)
                assertEquals(
                    "parent=$parent running=$running",
                    running && parent == Lifecycle.State.RESUMED,
                    open
                )
            }
        }
    }

    /** The camera owner must never be held ABOVE the body's own lifecycle. */
    @Test
    fun `the camera state never exceeds the parent state`() {
        Lifecycle.State.entries.forEach { parent ->
            listOf(true, false).forEach { running ->
                val state = mirrorCameraLifecycleState(parent, running)
                assertTrue("parent=$parent running=$running gave $state", parent.isAtLeast(state))
            }
        }
    }

    /**
     * The closes the spec names, one by one: countdown ends, activity pauses (or the redirect picker
     * caps the body at STARTED), activity stops, and the overlay is destroyed.
     */
    @Test
    fun `countdown end, pause, picker, stop and destroy each close the camera`() {
        fun closed(parent: Lifecycle.State, running: Boolean) =
            !mirrorCameraLifecycleState(parent, running).isAtLeast(Lifecycle.State.STARTED)

        assertTrue("countdown ended", closed(Lifecycle.State.RESUMED, running = false))
        assertTrue("paused or picker open", closed(Lifecycle.State.STARTED, running = true))
        assertTrue("stopped", closed(Lifecycle.State.CREATED, running = true))
        assertEquals(Lifecycle.State.DESTROYED, mirrorCameraLifecycleState(Lifecycle.State.DESTROYED, true))
        assertFalse("on screen and waiting", closed(Lifecycle.State.RESUMED, running = true))
    }
}
