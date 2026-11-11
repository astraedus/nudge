package com.astraedus.nudge.service

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.windows.ScreenWindow
import com.astraedus.nudge.domain.windows.WindowBounds
import com.astraedus.nudge.domain.windows.WindowKind
import com.astraedus.nudge.domain.windows.WindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #181: "Bro. wtf. You've bounced through 4 apps" after opening TWO apps (Nudge 1.29.0,
 * Android 17, Pixel 10 Pro, Delay mode, with a cooldown).
 *
 * The check-in needs [BounceDetector.MIN_APPS] distinct packages, so two packages that are not apps
 * the user opened reached `BounceDetector.onAppOpened`. The only door in is a
 * [ForegroundSignal.AppWindow] from the one [EventClassifier], and the classifier cannot tell a
 * system sheet from an app: a share sheet, an account picker, the photo picker or a credential
 * prompt is a translucent ACTIVITY in its own package, and its window is a focused APPLICATION
 * window like any app's. Its package is on no list, and no list could hold them all.
 *
 * Everything that decides is real: the classifier built from the production constants, the
 * [BounceCheckIn] adapter and the [BounceDetector] with its production exclusions. Walls go through
 * the same entry points the service calls (`onBlockLaunched` for an overlay, `onKicked` for an
 * auto-kick). The clock is injected.
 *
 * Layer L2 (classified-event replay). The photo-picker case replays a committed device capture;
 * the other sheet packages are Pixel device data (research for #181, no capture of them yet).
 */
class BounceNonAppWindowTest {

    private val nudge = BuildConfig.APPLICATION_ID
    private val launcher = "com.google.android.apps.nexuslauncher"

    // Real apps a user opens. These are just names, so any distinct launchable app would do.
    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val reddit = "com.reddit.frontpage"
    private val discord = "com.discord"

    /**
     * Surfaces that arrive as their own package, in an activity window, without being an app the
     * user opened. Package -> the activity class it shows. None has a launcher entry.
     */
    private val sheets = linkedMapOf(
        // Android 14+ share sheet (moved out of `android` into its own module).
        "com.android.intentresolver" to "com.android.intentresolver.ChooserActivity",
        // Google Play services: account picker, One Tap sign-in, Fast Pair half-sheet.
        "com.google.android.gms" to "com.google.android.gms.common.account.AccountPickerActivity",
        // The system photo picker (captured on the bench: picker-subflow-keeps-sitting.jsonl).
        "com.google.android.providers.media.module" to
            "com.android.providers.media.photopicker.PhotoPickerActivity",
        // Credential Manager's passkey / password bottom sheet.
        "com.android.credentialmanager" to "com.android.credentialmanager.CredentialSelectorActivity",
        // Android System Intelligence (Magic Cue, Now Playing, smart text selection surfaces).
        "com.google.android.as" to "com.google.android.apps.miphone.aiai.app.AiAiActivity",
        // The screenshot editor opened from the screenshot preview.
        "com.google.android.markup" to "com.google.android.markup.AnnotateActivity",
    )

    private var now = 9_000_000L
    private var snapshot: WindowSnapshot? = null
    private val posted = mutableListOf<BounceAlert>()

    /** Every AppWindow the classifier handed the check-in while it was tracking, for the message. */
    private val fedWhileArmed = mutableListOf<String>()

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { snapshot }
    )

    private val checkIn = BounceCheckIn(
        detector = BounceDetector(excludedPackages = BounceDetector.neverCountedPackages(nudge)),
        notify = { posted += it },
        clock = { now }
    ).also { it.setEnabled(true) }

    // ---- The one path a window event takes in the service: record -> classify -> check-in. ----

    private fun classify(record: AccessibilityEventRecord): ForegroundSignal = classifier.classify(
        record,
        currentImePackage = null,
        launcherPackages = setOf(launcher),
        pipOnlyPackages = emptySet()
    )

    private fun feed(record: AccessibilityEventRecord) {
        val signal = classify(record)
        if (checkIn.isTracking && signal is ForegroundSignal.AppWindow) fedWhileArmed += signal.packageName
        checkIn.onForegroundSignal(signal)
    }

    private var nextWindowId = 100

    private fun stateChanged(pkg: String, className: String, windowId: Int = nextWindowId++) =
        AccessibilityEventRecord(
            type = A11yEventType.WINDOW_STATE_CHANGED,
            packageName = pkg,
            className = className,
            windowId = windowId,
            eventTimeMs = now
        )

    private fun open(pkg: String) {
        now += STEP_MS
        feed(stateChanged(pkg, "$pkg.MainActivity"))
    }

    private fun sheet(pkg: String) {
        now += STEP_MS
        feed(stateChanged(pkg, sheets.getValue(pkg)))
    }

    private fun home() {
        now += STEP_MS
        feed(stateChanged(launcher, "$launcher.NexusLauncherActivity"))
    }

    /** A Delay overlay goes up over [pkg]: the gate launched it, and its own window arrives. */
    private fun delayWall(pkg: String) {
        now += 200L
        checkIn.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, pkg)
        feed(stateChanged(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity"))
    }

    /** The delay finished and the user is let through: the walled app is back in front. */
    private fun letThrough(pkg: String) {
        now += STEP_MS
        feed(stateChanged(pkg, "$pkg.MainActivity"))
    }

    private fun failureMessage() =
        "fired ${posted.map { "${it.unit}=${it.count}" }}; AppWindow signals counted while armed: $fedWhileArmed"

    // ---- #181 ----

    @Test
    fun `two delay-walled apps plus system sheets never fire the check-in`() {
        // Enough sheets to make up MIN_APPS with the two real apps, from the class, not one name.
        val needed = BounceDetector.MIN_APPS - 2
        assertTrue("fixture needs $needed sheets", sheets.size >= needed)

        sheets.keys.windowed(needed).forEach { picked ->
            reset()
            open(instagram)
            delayWall(instagram)
            letThrough(instagram)
            sheet(picked[0]) // e.g. "share to..." from inside Instagram
            letThrough(instagram)
            home()
            open(youtube)
            delayWall(youtube) // wall 2
            letThrough(youtube)
            picked.drop(1).forEach { sheet(it); letThrough(youtube) }

            assertEquals("sheets $picked: ${failureMessage()}", emptyList<BounceAlert>(), posted)
        }
    }

    @Test
    fun `one walled app with auto-kick and a cooldown re-open plus sheets never fires`() {
        // The reporter's "a cooldown of 60 seconds": the kick takes them home, the re-open inside the
        // cooldown is a fresh overlay for the same app, and a Home in between makes it wall 2.
        open(instagram)
        delayWall(instagram) // wall 1
        letThrough(instagram)
        sheet("com.google.android.gms") // an account or sign-in sheet over Instagram
        letThrough(instagram)
        now += STEP_MS
        checkIn.onKicked(instagram, browserPackage = null) // same app, not left: not a new wall
        home() // AutoKickExecutor.goHome
        open(youtube) // the second real app, not walled
        sheet("com.android.intentresolver")
        open(instagram)
        delayWall(instagram) // the cooldown overlay: wall 2

        assertEquals(failureMessage(), emptyList<BounceAlert>(), posted)
    }

    @Test
    fun `the photo picker from a device capture is not counted as an app`() {
        // picker-subflow-keeps-sitting.jsonl: Google Keep under a DELAY rule, then the system photo
        // picker over it, then Keep again. Replayed with its own timestamps as the clock.
        val keep = "com.google.android.keep"
        val records = A11yCapture.load("picker-subflow-keeps-sitting")
        val firstOverlay = records.first {
            it.packageName == nudge && it.className?.endsWith(".BlockOverlayActivity") == true
        }
        records.forEach { record ->
            now = record.eventTimeMs
            if (record === firstOverlay) checkIn.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, keep)
            feed(record)
        }
        assertTrue("the capture must arm the check-in", checkIn.isTracking)

        // Then the user goes on to two more real apps, one of them walled: three apps in all.
        home()
        open(instagram)
        delayWall(instagram) // wall 2
        letThrough(instagram)
        open(reddit)

        assertEquals(
            "Keep, Instagram and Reddit are three apps. ${failureMessage()}",
            emptyList<BounceAlert>(),
            posted
        )
    }

    @Test
    fun `a sheet that owns the focused application window still classifies as an app`() {
        // Why the classifier cannot be the fix: with the full window list, the share sheet is the
        // focused APPLICATION window and the app it was opened from is unfocused behind it. Every
        // piece of window evidence the classifier has says "this is the app in front".
        val share = "com.android.intentresolver"
        snapshot = WindowSnapshot(
            windows = listOf(
                window(id = 1, pkg = instagram, focused = false),
                window(id = 2, pkg = share, focused = true),
                ScreenWindow(
                    windowId = 3, kind = WindowKind.SYSTEM, packageName = "com.android.systemui",
                    isActive = false, isFocused = false, isPictureInPicture = false,
                    bounds = WindowBounds(0, 0, DISPLAY_W, 140)
                )
            ),
            displayWidth = DISPLAY_W,
            displayHeight = DISPLAY_H
        )
        val signal = classify(stateChanged(share, sheets.getValue(share), windowId = 2))
        assertEquals(ForegroundSignal.AppWindow(share), signal)
    }

    @Test
    fun `positive control - four real apps across two walls fire exactly once`() {
        val realApps = listOf(instagram, youtube, reddit, discord)
        assertEquals("fixture is MIN_APPS real apps", BounceDetector.MIN_APPS, realApps.size)

        open(instagram)
        delayWall(instagram)
        letThrough(instagram)
        home()
        open(reddit)
        open(discord)
        open(youtube)
        delayWall(youtube)

        assertEquals(failureMessage(), 1, posted.size)
        assertEquals(BounceDetector.MIN_APPS, posted.single().count)
        assertEquals(BounceAlert.Unit.APPS, posted.single().unit)

        // And once only: the cooldown holds it.
        letThrough(youtube)
        home()
        open(instagram)
        delayWall(instagram)
        assertEquals(1, posted.size)
    }

    private fun reset() {
        checkIn.reset()
        posted.clear()
        fedWhileArmed.clear()
        now += BounceDetector.WINDOW_MS * 10
    }

    private fun window(id: Int, pkg: String, focused: Boolean) = ScreenWindow(
        windowId = id,
        kind = WindowKind.APPLICATION,
        packageName = pkg,
        isActive = focused,
        isFocused = focused,
        isPictureInPicture = false,
        bounds = WindowBounds(0, 0, DISPLAY_W, DISPLAY_H)
    )

    private companion object {
        /** Well inside [BounceDetector.WINDOW_MS] for every sequence in this file. */
        const val STEP_MS = 5_000L
        const val DISPLAY_W = 1280
        const val DISPLAY_H = 2856
    }
}
