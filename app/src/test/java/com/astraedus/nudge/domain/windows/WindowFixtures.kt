package com.astraedus.nudge.domain.windows

/**
 * Window lists shaped like the ones in issues #76 and #71, for the window-evidence tests.
 *
 * Bounds are a 1080x2340 portrait panel (Pixel-class and S24-class both sit near it). A fullscreen
 * app on API 26-29 with an opaque navigation bar stops at the bar, which is why [fullscreenApp]
 * defaults to 2214 tall rather than 2340: the fullscreen test must survive that.
 */
internal object WindowFixtures {
    const val DISPLAY_W = 1080
    const val DISPLAY_H = 2340

    val FULL = WindowBounds(0, 0, DISPLAY_W, 2214)
    val FREEFORM = WindowBounds(120, 600, 900, 1700)
    val KEYBOARD = WindowBounds(0, 1500, DISPLAY_W, DISPLAY_H)

    fun fullscreenApp(
        id: Int,
        pkg: String,
        focused: Boolean = true,
        bounds: WindowBounds = FULL
    ) = ScreenWindow(
        windowId = id,
        kind = WindowKind.APPLICATION,
        packageName = pkg,
        isActive = focused,
        isFocused = focused,
        isPictureInPicture = false,
        bounds = bounds
    )

    fun floatingApp(id: Int, pkg: String, focused: Boolean, bounds: WindowBounds = FREEFORM) =
        fullscreenApp(id, pkg, focused, bounds)

    fun window(id: Int, kind: WindowKind, bounds: WindowBounds = KEYBOARD) = ScreenWindow(
        windowId = id,
        kind = kind,
        packageName = null,
        isActive = false,
        isFocused = false,
        isPictureInPicture = false,
        bounds = bounds
    )

    fun snapshot(vararg windows: ScreenWindow) = WindowSnapshot(windows.toList(), DISPLAY_W, DISPLAY_H)
}
