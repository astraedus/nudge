package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.bounce.Countability
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.windows.ScreenWindow
import com.astraedus.nudge.domain.windows.WindowBounds
import com.astraedus.nudge.domain.windows.WindowKind
import com.astraedus.nudge.domain.windows.WindowSnapshot
import com.astraedus.nudge.service.BounceHarness.Companion.PIXEL_LAUNCHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #181: "Bro. wtf. You've bounced through 4 apps" after opening TWO apps (Nudge 1.29.0,
 * Android 17, Pixel 10 Pro, Delay mode, with a cooldown).
 *
 * The check-in needs [BounceDetector.MIN_APPS] distinct packages, so two packages that are not apps
 * the user opened reached `BounceDetector.onAppOpened`. The only door in is a
 * [ForegroundSignal.AppWindow] from the one `EventClassifier`, and the classifier cannot tell a
 * system sheet from an app: a share sheet, an account picker, the photo picker or a credential
 * prompt is a translucent ACTIVITY in its own package, and its window is a focused APPLICATION
 * window like any app's. Its package is on no list, and no list could hold them all. A launcher
 * the service has not resolved (an empty or stale launcher set, a launcher outside
 * `SYSTEM_PACKAGES`) comes through the same door.
 *
 * The fix: only an app counts, and an app is a package with a launcher entry that is not a home
 * screen (`AppCountability` over the package facts). None of the sheets has a launcher entry.
 *
 * Everything that decides is real ([BounceHarness]); the device is a [FakePackageFacts] and the
 * clock is injected. Layer L2 (classified-event replay). The photo-picker case replays a committed
 * device capture; the other sheet packages are Pixel device data (research for #181, no capture of
 * them yet). The bench captures are replayed in [BounceCaptureReplayTest].
 */
class BounceNonAppWindowTest {

    // Real apps a user opens. These are just names, so any distinct launchable app would do.
    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val reddit = "com.reddit.frontpage"
    private val discord = "com.discord"
    private val keep = "com.google.android.keep"
    private val realApps = listOf(instagram, youtube, reddit, discord)

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

    private fun device() = FakePackageFacts(
        launchable = realApps.toSet() + keep,
        home = setOf(PIXEL_LAUNCHER, THIRD_PARTY_LAUNCHER),
        caches = true
    )

    private val h = BounceHarness(device())

    private fun BounceHarness.sheet(pkg: String) = surface(pkg, sheets.getValue(pkg))

    /** The reporter's shape: two Delay-walled apps, let through, with [picked] sheets in between. */
    private fun BounceHarness.twoWalledAppsWith(picked: List<String>) {
        open(instagram)
        delayWall(instagram)
        open(instagram) // let through
        sheet(picked[0]) // e.g. "share to..." from inside Instagram
        open(instagram)
        home()
        open(youtube)
        delayWall(youtube) // wall 2
        open(youtube)
        picked.drop(1).forEach { sheet(it); open(youtube) }
    }

    /** Every combination of [size] sheets, so a fix that works for one name only is caught. */
    private fun sheetSets(size: Int): List<List<String>> {
        val all = sheets.keys.toList()
        fun from(start: Int, left: Int): List<List<String>> =
            if (left == 0) {
                listOf(emptyList())
            } else {
                (start..all.size - left).flatMap { i -> from(i + 1, left - 1).map { listOf(all[i]) + it } }
            }
        return from(0, size)
    }

    // ---- #181: sheets ----

    @Test
    fun `two delay-walled apps plus system sheets never fire, for every set of sheets`() {
        val needed = BounceDetector.MIN_APPS - 2
        val sets = sheetSets(needed)
        assertTrue("fixture needs $needed sheets", sets.isNotEmpty())

        // Every combination, and every failure reported, not just the first.
        val failures = sets.mapNotNull { picked ->
            h.reset()
            h.twoWalledAppsWith(picked)
            if (h.posted.isEmpty()) null else "sheets $picked: ${h.failureMessage()}"
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test
    fun `the pre-181 rule really would have fired on every set of sheets`() {
        // The counterfactual inside the suite: count every AppWindow, as 1.29.0 did, and the same
        // sequences fire. If this ever stops holding, the test above proves nothing.
        val missed = sheetSets(BounceDetector.MIN_APPS - 2).mapNotNull { picked ->
            val old = BounceHarness(device(), countEverything = true)
            old.twoWalledAppsWith(picked)
            if (old.posted.size == 1) null else "sheets $picked did not fire under the old rule"
        }
        assertEquals(missed.joinToString("\n"), emptyList<String>(), missed)
    }

    @Test
    fun `every sheet is answered NOT_AN_APP once its facts land`() {
        val wrong = sheets.keys.mapNotNull { pkg ->
            assertEquals("$pkg before its lookup", Countability.UNKNOWN, h.countability(pkg))
            h.drainLookups()
            h.countability(pkg).takeIf { it != Countability.NOT_AN_APP }?.let { "$pkg -> $it" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `one walled app with auto-kick and a cooldown re-open plus sheets never fires`() {
        // The reporter's "a cooldown of 60 seconds": the kick takes them home, the re-open inside the
        // cooldown is a fresh overlay for the same app, and a Home in between makes it wall 2.
        h.open(instagram)
        h.delayWall(instagram) // wall 1
        h.open(instagram)
        h.sheet("com.google.android.gms") // an account or sign-in sheet over Instagram
        h.open(instagram)
        h.now += BounceHarness.STEP_MS
        h.checkIn.onKicked(instagram, browserPackage = null) // same app, not left: not a new wall
        h.home() // AutoKickExecutor.goHome
        h.open(youtube) // the second real app, not walled
        h.sheet("com.android.intentresolver")
        h.open(instagram)
        h.delayWall(instagram) // the cooldown overlay: wall 2

        assertEquals(h.failureMessage(), emptyList<BounceAlert>(), h.posted)
    }

    @Test
    fun `the photo picker from a device capture is not counted as an app`() {
        // picker-subflow-keeps-sitting.jsonl: Google Keep under a DELAY rule, then the system photo
        // picker over it, then Keep again. Replayed with its own timestamps as the clock.
        val records = A11yCapture.load("picker-subflow-keeps-sitting")
        val firstOverlay = records.first {
            it.packageName == h.nudge && it.className?.endsWith(".BlockOverlayActivity") == true
        }
        records.forEach { record ->
            h.now = record.eventTimeMs
            if (record === firstOverlay) {
                h.checkIn.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, keep)
                h.drainLookups()
            }
            h.feed(record)
        }
        assertTrue("the capture must arm the check-in", h.checkIn.isTracking)
        assertTrue(
            "the picker must have reached the check-in as an app window: ${h.fedWhileArmed}",
            "com.google.android.providers.media.module" in h.fedWhileArmed
        )

        // Then the user goes on to two more real apps, one of them walled: three apps in all.
        h.home()
        h.open(instagram)
        h.delayWall(instagram) // wall 2
        h.open(instagram)
        h.open(reddit)

        assertEquals(
            "Keep, Instagram and Reddit are three apps. ${h.failureMessage()}",
            emptyList<BounceAlert>(),
            h.posted
        )
    }

    @Test
    fun `a sheet that owns the focused application window still classifies as an app`() {
        // Why the classifier cannot be the fix: with the full window list, the share sheet is the
        // focused APPLICATION window and the app it was opened from is unfocused behind it. Every
        // piece of window evidence the classifier has says "this is the app in front".
        val share = "com.android.intentresolver"
        h.snapshot = WindowSnapshot(
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
        val signal = h.classify(h.stateChanged(share, sheets.getValue(share), windowId = 2))
        assertEquals(ForegroundSignal.AppWindow(share), signal)
    }

    // ---- What counts as leaving ----

    @Test
    fun `a sheet over the walled app is not leaving it, so the overlay coming back is not a new wall`() {
        // Four real apps, but only ONE wall: the second Instagram overlay follows a sheet, not a trip
        // anywhere. Counting the sheet as leaving would make it wall 2 and fire.
        h.open(instagram)
        h.delayWall(instagram)
        h.sheet("com.google.android.gms")
        h.delayWall(instagram) // the overlay re-launched over the same app: not a new wall
        h.open(reddit)
        h.open(discord)
        h.open(youtube)

        assertEquals(h.failureMessage(), emptyList<BounceAlert>(), h.posted)
    }

    @Test
    fun `positive half - going home in between does make it a new wall`() {
        h.open(instagram)
        h.delayWall(instagram)
        h.home()
        h.delayWall(instagram) // wall 2: they left and came back
        h.open(reddit)
        h.open(discord)
        h.open(youtube)

        assertEquals(h.failureMessage(), 1, h.posted.size)
        assertEquals(BounceDetector.MIN_APPS, h.posted.single().count)
    }

    @Test
    fun `an app whose facts were not cached on the way in still counts as leaving`() {
        // Every app is a cache miss the first time (a Delay-only user has no category rule that
        // would have read them). Leaving is judged when the next wall arrives, by which time the
        // facts have landed, so Instagram -> other apps -> back to Instagram is a second wall.
        h.open(instagram)
        h.delayWall(instagram)
        h.open(reddit)
        h.open(discord)
        h.open(youtube)
        h.open(instagram)
        h.delayWall(instagram) // wall 2, same app, after leaving through three uncached apps

        assertEquals(h.failureMessage(), 1, h.posted.size)
        assertEquals(BounceDetector.MIN_APPS, h.posted.single().count)
        assertEquals(setOf(instagram, reddit, discord, youtube), h.posted.single().packages.toSet())
    }

    // ---- Lookups ----

    @Test
    fun `positive control - four real apps across two walls fire exactly once, from a cold cache`() {
        assertEquals("fixture is MIN_APPS real apps", BounceDetector.MIN_APPS, realApps.size)
        assertTrue("the cache starts cold", realApps.all { h.facts.cachedOrNull(it) == null })

        h.open(instagram)
        h.delayWall(instagram)
        h.open(instagram)
        h.home()
        h.open(reddit)
        h.open(discord)
        h.open(youtube)
        h.delayWall(youtube)

        assertEquals(h.failureMessage(), 1, h.posted.size)
        assertEquals(BounceDetector.MIN_APPS, h.posted.single().count)
        assertEquals(BounceAlert.Unit.APPS, h.posted.single().unit)
        assertEquals(2, h.posted.single().walls)

        // And once only: the cooldown holds it.
        h.open(youtube)
        h.home()
        h.open(instagram)
        h.delayWall(instagram)
        assertEquals(1, h.posted.size)
    }

    @Test
    fun `a lookup that lands after the last event still fires, through the re-check`() {
        h.lookupsLand = false
        h.open(instagram)
        h.delayWall(instagram)
        h.home()
        h.delayWall(instagram) // wall 2: home in between, so known without any facts
        h.open(reddit)
        h.open(discord)
        h.open(youtube)
        assertEquals("nothing is known yet, so nothing counts", emptyList<BounceAlert>(), h.posted)

        h.drainLookups() // the IO reads complete

        assertEquals(h.failureMessage(), 1, h.posted.size)
        assertEquals(BounceDetector.MIN_APPS, h.posted.single().count)
    }

    @Test
    fun `lookups that never land never fire, and each package is asked for once`() {
        h.lookupsLand = false
        repeat(3) {
            h.open(instagram)
            h.delayWall(instagram)
            h.home()
            realApps.forEach { h.open(it) }
            h.delayWall(youtube)
        }
        assertEquals(h.failureMessage(), emptyList<BounceAlert>(), h.posted)
        assertEquals("one lookup per package, not one per event", realApps.size, h.lookupsRequested)
    }

    @Test
    fun `a failed lookup is not retried and never counts`() {
        val broken = "com.example.unreadable"
        val facts = device().apply {
            launchable = launchable + broken
            failing = setOf(broken)
        }
        val b = BounceHarness(facts)
        b.open(instagram)
        b.delayWall(instagram)
        b.home()
        b.delayWall(instagram) // wall 2
        b.open(reddit)
        repeat(5) {
            b.open(broken)
            b.open(reddit)
        }
        b.open(discord) // Instagram, Reddit, Discord known; the unreadable one would be the 4th

        assertEquals(b.failureMessage(), emptyList<BounceAlert>(), b.posted)
        assertEquals(Countability.UNKNOWN, b.countability(broken))
        assertEquals("asked once, never again: no retry loop", 1, facts.readsOf(broken))
    }

    // ---- #181: the launcher (the 2 apps + Nudge + launcher = 4 arithmetic) ----

    @Test
    fun `a launcher outside SYSTEM_PACKAGES arriving as an app window while armed does not count`() {
        assertFalse(
            "fixture: the launcher must not be a known system package",
            THIRD_PARTY_LAUNCHER in NudgeAccessibilityService.SYSTEM_PACKAGES
        )
        // The service has not resolved its launcher set yet (empty): the classifier lets it through.
        h.launcherSet = emptySet()
        assertEquals(
            ForegroundSignal.AppWindow(THIRD_PARTY_LAUNCHER),
            h.classify(h.stateChanged(THIRD_PARTY_LAUNCHER, "$THIRD_PARTY_LAUNCHER.Launcher"))
        )

        h.open(instagram)
        h.delayWall(instagram)
        h.surface(THIRD_PARTY_LAUNCHER, "$THIRD_PARTY_LAUNCHER.Launcher")
        h.open(youtube)
        h.delayWall(youtube) // wall 2
        h.surface(THIRD_PARTY_LAUNCHER, "$THIRD_PARTY_LAUNCHER.Launcher")
        h.open(reddit) // three real apps and the launcher

        assertEquals(h.failureMessage(), emptyList<BounceAlert>(), h.posted)
        assertTrue(
            "the trace says why: ${h.skipped}",
            (THIRD_PARTY_LAUNCHER to Countability.HOME) in h.skipped
        )
    }

    @Test
    fun `a launcher is not counted even before its facts land`() {
        h.launcherSet = emptySet()
        h.lookupsLand = false
        h.open(instagram)
        h.delayWall(instagram)
        h.surface(THIRD_PARTY_LAUNCHER, "$THIRD_PARTY_LAUNCHER.Launcher")
        h.open(youtube)
        h.delayWall(youtube)
        h.open(reddit)

        assertEquals(h.failureMessage(), emptyList<BounceAlert>(), h.posted)
        assertEquals(Countability.UNKNOWN, h.countability(THIRD_PARTY_LAUNCHER))
    }

    @Test
    fun `a stale launcher set still knows a newly chosen launcher is home`() {
        // The service resolved the Pixel launcher minutes ago; the user has since switched to another.
        h.launcherSet = setOf(PIXEL_LAUNCHER)
        h.countability(THIRD_PARTY_LAUNCHER)
        h.drainLookups()
        assertEquals(Countability.HOME, h.countability(THIRD_PARTY_LAUNCHER))
        // And the resolved set alone answers too, with no facts read at all.
        assertEquals(Countability.HOME, h.countability(PIXEL_LAUNCHER))
        assertEquals(0, h.facts.readsOf(PIXEL_LAUNCHER))
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
        /** A launcher a user installs (Nova's package): not in SYSTEM_PACKAGES, a home screen. */
        const val THIRD_PARTY_LAUNCHER = "com.teslacoilsw.launcher"
        const val DISPLAY_W = 1280
        const val DISPLAY_H = 2856
    }
}
