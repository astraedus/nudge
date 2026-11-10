package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.domain.model.BlockMode

/**
 * Every screen [BlockOverlayActivity] can show, named the way the activity picks it: the block's
 * mode, with Nuke's own screen split out of HARD_BLOCK.
 */
internal enum class OverlayScreen {
    HARD_BLOCK,
    NUKE,
    DELAY,
    HOLD,
    BREATHING,
    MIRROR;

    companion object {
        /**
         * The screen the activity renders for [mode]. Mirrors its `when`: [nuke] only changes a
         * HARD_BLOCK (Nuke's decision is always one). Null for [BlockMode.NONE], which the
         * activity never renders.
         */
        fun of(mode: BlockMode, nuke: Boolean): OverlayScreen? = when (mode) {
            BlockMode.NONE -> null
            BlockMode.HARD_BLOCK -> if (nuke) NUKE else HARD_BLOCK
            BlockMode.DELAY -> DELAY
            BlockMode.HOLD -> HOLD
            BlockMode.BREATHING -> BREATHING
            BlockMode.MIRROR -> MIRROR
        }
    }
}

/** What a block screen is drawn on. */
internal enum class Backdrop {
    /** The mirrored front camera under a scrim, with the screen's own content over it. */
    CAMERA,

    /** The screen's own plain background, exactly as it is with the setting off. */
    PLAIN
}

/**
 * The "camera on block screens" setting as pure decisions, so they are JVM-tested; the CameraX
 * calls in [CameraBackdrop] only feed them.
 *
 * Same rule as MIRROR ([MirrorFallback]): the camera is a picture, never a gate. Only the
 * background changes. What the screen says, how long it holds, which exits it offers and what they
 * do are the screen's own, identical on either backdrop, so a camera problem can only ever cost the
 * user the picture.
 */
internal object BlockBackdrop {

    /**
     * Whether [screen] tries the camera at all with the setting [settingOn]. Every screen the
     * overlay shows does, except MIRROR, whose whole body is already the camera (a second
     * backdrop would open the same camera twice).
     */
    fun wantsCamera(settingOn: Boolean, screen: OverlayScreen): Boolean =
        settingOn && screen != OverlayScreen.MIRROR

    /**
     * The backdrop to draw: the camera only when the screen wants it and the camera can be shown
     * (MIRROR's own test: permission, a front camera, nothing failed this block). [cameraFailed]
     * is sticky for the delivery, so a flapping camera does not flicker the screen.
     */
    fun backdropFor(
        settingOn: Boolean,
        screen: OverlayScreen,
        permissionGranted: Boolean,
        hasFrontCamera: Boolean,
        cameraFailed: Boolean
    ): Backdrop =
        if (wantsCamera(settingOn, screen) &&
            MirrorFallback.bodyFor(permissionGranted, hasFrontCamera, cameraFailed) == MirrorBody.CAMERA
        ) {
            Backdrop.CAMERA
        } else {
            Backdrop.PLAIN
        }
}
