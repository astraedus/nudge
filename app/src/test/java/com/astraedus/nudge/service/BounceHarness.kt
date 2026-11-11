package com.astraedus.nudge.service

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.bounce.AppCountability
import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.bounce.Countability
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.windows.WindowSnapshot
import kotlinx.coroutines.runBlocking

/**
 * The bounce check-in as the service runs it, for #181's tests: one window event goes record ->
 * the real [EventClassifier] (built from the production constants) -> the real [BounceCheckIn] ->
 * the real [BounceDetector] with its production exclusions and the production [AppCountability].
 *
 * Only the device is faked: [facts] caches like the real `AndroidPackageFacts`, and the off-thread
 * lookup the service runs on its IO scope is a queue drained by [drainLookups], by default right
 * after each event (the read takes milliseconds; the user's next switch takes seconds). Set
 * [lookupsLand] false for the worst case, a lookup that never comes back. The clock is [now],
 * injected; nothing here reads a wall clock.
 */
class BounceHarness(
    /** What the device holds. Real apps are launchable; launchers are home screens. */
    val facts: FakePackageFacts,
    /** The service's resolved launcher set, as `launcherPackagesCached` holds it. */
    var launcherSet: Set<String> = setOf(PIXEL_LAUNCHER),
    /** The pre-#181 rule (every AppWindow is an app), for the counterfactual tests only. */
    countEverything: Boolean = false
) {
    val nudge: String = BuildConfig.APPLICATION_ID

    var now = 9_000_000L
    var snapshot: WindowSnapshot? = null
    val posted = mutableListOf<BounceAlert>()

    /** Every AppWindow the classifier handed the check-in while it was tracking, for messages. */
    val fedWhileArmed = mutableListOf<String>()

    /** Packages the countability left out, and why, as the debug trace would print them. */
    val skipped = mutableListOf<Pair<String, Countability>>()

    var lookupsLand = true
    var lookupsRequested = 0
        private set
    private val pendingLookups = ArrayDeque<String>()

    val countability = AppCountability(
        facts = facts,
        homePackages = { launcherSet },
        requestLookup = { pkg ->
            lookupsRequested++
            pendingLookups += pkg
        }
    )

    val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { snapshot }
    )

    val checkIn = BounceCheckIn(
        detector = BounceDetector(
            excludedPackages = BounceDetector.neverCountedPackages(nudge),
            countability = if (countEverything) { _ -> Countability.COUNTS } else countability,
            onNotCounted = { pkg, why -> skipped += pkg to why }
        ),
        notify = { posted += it },
        clock = { now }
    ).also { it.setEnabled(true) }

    fun classify(record: AccessibilityEventRecord): ForegroundSignal = classifier.classify(
        record,
        currentImePackage = null,
        launcherPackages = launcherSet,
        pipOnlyPackages = emptySet()
    )

    fun feed(record: AccessibilityEventRecord) {
        val signal = classify(record)
        if (checkIn.isTracking && signal is ForegroundSignal.AppWindow) fedWhileArmed += signal.packageName
        checkIn.onForegroundSignal(signal)
        if (lookupsLand) drainLookups()
    }

    /** What the service's IO coroutine does with each lookup: read, then re-check if it landed. */
    fun drainLookups() {
        while (pendingLookups.isNotEmpty()) {
            val pkg = pendingLookups.removeFirst()
            runCatching { runBlocking { facts.factsFor(pkg) } }
            if (countability.lookupFinished(pkg)) checkIn.onFactsLanded()
        }
    }

    private var nextWindowId = 100

    fun stateChanged(pkg: String, className: String, windowId: Int = nextWindowId++) =
        AccessibilityEventRecord(
            type = A11yEventType.WINDOW_STATE_CHANGED,
            packageName = pkg,
            className = className,
            windowId = windowId,
            eventTimeMs = now
        )

    /** The user opens [pkg] (or it comes back to the front). */
    fun open(pkg: String) {
        now += STEP_MS
        feed(stateChanged(pkg, "$pkg.MainActivity"))
    }

    /** A surface of [pkg] showing [className] comes to the front. */
    fun surface(pkg: String, className: String) {
        now += STEP_MS
        feed(stateChanged(pkg, className))
    }

    fun home() {
        now += STEP_MS
        feed(stateChanged(PIXEL_LAUNCHER, "$PIXEL_LAUNCHER.NexusLauncherActivity"))
    }

    /** A Delay overlay goes up over [pkg]: the gate launched it, and its own window arrives. */
    fun delayWall(pkg: String) {
        now += 200L
        checkIn.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, pkg)
        if (lookupsLand) drainLookups()
        feed(stateChanged(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity"))
    }

    /**
     * Replay a committed capture with its own timestamps as the clock, putting each of its
     * `# WALL <ts> <overlay|kick> <package>` header lines through the entry point the service uses
     * for it, at its time. [rewrite] maps each record first (to model a device that differs).
     */
    fun replay(name: String, rewrite: (AccessibilityEventRecord) -> AccessibilityEventRecord = { it }) {
        val walls = walls(name).toMutableList()
        A11yCapture.load(name).map(rewrite).forEach { record ->
            while (walls.isNotEmpty() && walls.first().ts <= record.eventTimeMs) wall(walls.removeAt(0))
            now = record.eventTimeMs
            feed(record)
        }
        walls.forEach(::wall)
    }

    private fun wall(w: CapturedWall) {
        now = maxOf(now, w.ts)
        when (w.kind) {
            "overlay" -> checkIn.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, w.pkg)
            "kick" -> checkIn.onKicked(w.pkg, browserPackage = null)
            else -> error("unknown wall kind ${w.kind}")
        }
        if (lookupsLand) drainLookups()
    }

    fun reset() {
        checkIn.reset()
        posted.clear()
        fedWhileArmed.clear()
        skipped.clear()
        now += BounceDetector.WINDOW_MS * 10
    }

    fun failureMessage() =
        "fired ${posted.map { "${it.unit}=${it.count} ${it.packages}" }}; " +
            "AppWindow signals while armed: $fedWhileArmed; left out: $skipped"

    data class CapturedWall(val ts: Long, val kind: String, val pkg: String)

    companion object {
        const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"

        /** Well inside [BounceDetector.WINDOW_MS] for every sequence that uses it. */
        const val STEP_MS = 5_000L

        /** A capture's `# WALL` header lines. */
        fun walls(name: String): List<CapturedWall> = A11yCapture.header(name)
            .mapNotNull { WALL_LINE.find(it) }
            .map { m ->
                val (ts, kind, pkg) = m.destructured
                CapturedWall(ts.toLong(), kind, pkg)
            }

        /** `# WALL <event-time ms> <overlay|kick> <package>`; prose header lines never match. */
        private val WALL_LINE = Regex("""^#\s*WALL (\d+) (overlay|kick) (\S+)""")
    }
}
