package com.astraedus.nudge.ui.overlay

import androidx.lifecycle.Lifecycle

/** Which picture a MIRROR block shows for its wait. */
internal enum class MirrorBody {
    /** The front camera's live preview, with the countdown and the exits over it. */
    CAMERA,

    /** The plain DELAY body: the ring, the number and the exits, for the same wait. */
    DELAY
}

/**
 * The decisions a MIRROR block makes, as pure functions so they are JVM-tested; the CameraX calls
 * in [MirrorContent] only feed them.
 *
 * The rule they encode: the camera is a picture, never a gate. Whatever goes wrong with it, the
 * user still gets the same countdown and the same exits, so a camera problem can never become a
 * way into the app (no instant open, no screen without a countdown).
 */
internal object MirrorFallback {

    /**
     * The camera only when everything needed for it is there and nothing has failed this block.
     * [cameraFailed] is sticky for the delivery (the caller never sets it back), so a camera that
     * flaps between error and open does not flicker the screen between the two bodies.
     *
     * A DARK frame is not a failure and is not an input here at all: a phone lying face down, or
     * a dark room, is a working camera showing the truth.
     */
    fun bodyFor(permissionGranted: Boolean, hasFrontCamera: Boolean, cameraFailed: Boolean): MirrorBody =
        if (permissionGranted && hasFrontCamera && !cameraFailed) MirrorBody.CAMERA else MirrorBody.DELAY

    /**
     * Whether a CameraX `CameraState.error` means falling back. Every error does, the recoverable
     * ones included: ERROR_CAMERA_IN_USE and ERROR_MAX_CAMERAS_IN_USE are "another app has the
     * camera", and waiting out a retry would leave the user staring at black for an unknown part
     * of the wait. [errorCode] is null when the state carries no error.
     */
    fun isFailure(errorCode: Int?): Boolean = errorCode != null
}

/**
 * The state a [FrontCameraPreview]'s lifecycle owner is held at, given the screen's [parent] state.
 * Shared by the MIRROR body ([open] = the wait is still running) and the "camera on block screens"
 * backdrop ([open] = true for as long as it is drawn).
 *
 * CameraX opens the camera when its owner reaches STARTED and closes it when the owner drops below,
 * so this decides exactly when the camera is on: only while the block is RESUMED (on screen, not
 * behind the redirect picker, which caps [parent] at STARTED) AND [open]. The moment the wait ends,
 * the user walks away, the picker opens or the activity pauses, it falls to CREATED and the camera
 * closes, before the overlay has even finished. Never above [parent], and a DESTROYED or
 * INITIALIZED parent passes straight through.
 */
internal fun mirrorCameraLifecycleState(parent: Lifecycle.State, open: Boolean): Lifecycle.State =
    when {
        open && parent.isAtLeast(Lifecycle.State.RESUMED) -> Lifecycle.State.RESUMED
        parent.isAtLeast(Lifecycle.State.CREATED) -> Lifecycle.State.CREATED
        else -> parent
    }
