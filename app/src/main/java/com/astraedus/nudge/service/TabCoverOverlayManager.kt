package com.astraedus.nudge.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.surfaces.TabCoverEffect
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement
import com.astraedus.nudge.domain.surfaces.TabCoverPresence
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tab-vanish cover: one solid, TOUCHABLE `TYPE_ACCESSIBILITY_OVERLAY` window painted exactly
 * over a host app's nav-bar tab, in the nav bar's own colour, so the icon disappears and the tap is
 * eaten.
 *
 * Structurally a sibling of [CounterOverlayManager] and [TimeRemainingOverlayManager] — same
 * `serviceContext` / [setServiceContext] / [clearServiceContext] dance, same never-throw window
 * work — and deliberately different in exactly two places, both of which are the feature.
 *
 * ## It does NOT set `FLAG_NOT_TOUCHABLE`, and that is the point
 *
 * [CounterOverlayManager] and [TimeRemainingOverlayManager] both set
 * `WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE`: they are pills the user must be able to tap
 * *through*, because they sit over a live app the user is still using. This window is the opposite
 * kind of thing. Eating the tap on the blocked tab IS the enforcement — with that flag the icon
 * would be hidden and the invisible tab would still open Reels, which is worse than no feature at
 * all because the user cannot see what they are hitting. Do not "fix" the inconsistency with the two
 * managers next door; they are inconsistent on purpose.
 *
 * ## It counts NOTHING
 *
 * This class must never log a `UsageEvent`, never touch `UsageRepository`, and never call anything
 * named `claimConfrontation`. A tap the cover eats is **not a confrontation**: no block screen was
 * shown, the user was never told anything, and nothing was refused to their face. Counting it would
 * inflate the Blocked count exactly as issue #36 did — and unlike #36 it would inflate on ordinary
 * mis-taps at a nav bar, so the number would drift for users who never once tried to open Reels.
 * `TabCoverCountingContractTest` reads this file's source to keep it that way, the same way
 * `BlockedCountSemanticsContractTest` pins the counting rule it came from (LESSONS 2026-09-20).
 *
 * ## Why the views are `AwarenessOverlayWindow` types
 *
 * Issue [#41](https://github.com/astraedus/nudge/issues/41): a `TYPE_ACCESSIBILITY_OVERLAY` owned by
 * Nudge fires window and content events carrying OUR package. Without the identity class name,
 * `EventClassifier` reads them as [ForegroundSignal.OwnUi], `BlockLaunchGate.foregroundAfter` moves
 * the foreground to Nudge, and every subsequent block is dropped with `DROP_FOREGROUND_MOVED`. For
 * this window that failure is especially perverse: the cover would silently break blocking **for the
 * very app it is covering**. A bare `View`/`FrameLayout`/`LinearLayout` here is a regression;
 * `AwarenessOverlayContractTest` fails the build on one.
 */
@Singleton
class TabCoverOverlayManager @Inject constructor(
    // Deliberately UNUSED for window work. TYPE_ACCESSIBILITY_OVERLAY needs the SERVICE context for
    // both the window token AND view creation; building views from the application context throws
    // BadTokenException (the comment CounterOverlayManager already carries). Kept in the signature
    // because every overlay manager in this package takes it and Hilt provides it either way.
    @Suppress("UNUSED_PARAMETER")
    @ApplicationContext appContext: Context,
    private val logger: NudgeLogger
) : TabCoverOverlayManagerApi {

    private var overlayView: AwarenessOverlayWindow.Container? = null
    private var windowManager: WindowManager? = null
    private var currentPlacement: TabCoverPlacement? = null

    @Volatile
    private var isShowing = false

    @Volatile
    private var coveredPackage: String? = null

    // TYPE_ACCESSIBILITY_OVERLAY needs the service context for both the window token AND view
    // creation. Using ApplicationContext for views causes BadTokenException.
    @Volatile
    private var serviceContext: Context? = null

    /**
     * The main-thread handler, or null when there is no Looper to build one from.
     *
     * All `WindowManager` work must happen on the main thread: `addView` / `updateViewLayout` /
     * `removeView` touch the view hierarchy, and the accessibility callbacks that drive this class
     * are only *usually* on the service's main thread — a cache refresh or a coroutine tail calling
     * [apply] from a worker thread would otherwise corrupt the hierarchy in a way that reproduces
     * once a week and never in QA.
     *
     * Null is also what lets the JVM unit tests drive this class: `Looper.getMainLooper()` is a
     * throwing stub with no Android runtime, so the lazy resolves to null and [onMainThread] runs
     * inline. The production path never sees null.
     */
    private val mainHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    /**
     * How the cover view is constructed. Production has exactly one answer, written here as the
     * default; a JVM test substitutes a mock.
     *
     * The seam exists because of a hard fact about this repo's test setup, not as a preference. Every
     * `android.view.View` instance method is a THROWING stub in these JVM tests — `setBackgroundColor`,
     * `setContentDescription` and `setOnTouchListener` all raise
     * `RuntimeException("Method … not mocked")` — and `testOptions { unitTests.isReturnDefaultValues }`
     * is deliberately not set (it would silently turn every unmocked android call into a 0/null under
     * ~1550 existing tests). View CONSTRUCTORS, by contrast, are no-ops in that jar, so the only thing
     * standing between these tests and the real class is the setters.
     *
     * What that buys is the part of this class that actually carries bugs: the window LIFECYCLE —
     * added once and not on every content change, moved rather than re-added, torn down on exactly
     * the right signals. Those were untestable otherwise, because the first thing a show does is
     * paint the view.
     *
     * What it does NOT excuse: the view's TYPE. `AwarenessOverlayContractTest` reads this file's
     * source and fails the build if anything here constructs a bare framework widget, because a
     * bare widget silently breaks blocking for the app this window covers (issue #41). The lambda's
     * parameter is named `ctx` so that guard can see it.
     */
    @VisibleForTesting
    internal var coverViewFactory: (Context) -> AwarenessOverlayWindow.Container =
        { ctx -> AwarenessOverlayWindow.Container(ctx) }

    fun setServiceContext(ctx: Context) {
        serviceContext = ctx
        logger.d("tab cover service context set")
    }

    fun clearServiceContext() {
        hide()
        serviceContext = null
    }

    override fun isVisible(): Boolean = isShowing

    override fun coveredPackage(): String? = coveredPackage

    override fun shownPlacement(): TabCoverPlacement? = currentPlacement

    override fun apply(packageName: String, effect: TabCoverEffect, color: Int, label: String) {
        when (effect) {
            // Idempotent by construction: "no opinion" must cost nothing, because this arrives on
            // every content-change event while the user scrolls.
            is TabCoverEffect.None -> return
            is TabCoverEffect.Hide -> onMainThread { hideOnMainThread() }
            is TabCoverEffect.Show -> onMainThread {
                showOrMoveOnMainThread(packageName, effect.placement, color, label)
            }
        }
    }

    override fun hide() {
        onMainThread { hideOnMainThread() }
    }

    override fun onForegroundSignal(signal: ForegroundSignal) {
        // Every bit of the decision is TabCoverPresence's. Nothing about which signals mean "the
        // user left" may be re-stated here: the cover is itself an AwarenessOverlay, so a local
        // guess would order it away on its own window event (the issue #41 shape).
        if (TabCoverPresence.shouldKeep(signal, coveredPackage)) return
        if (!isShowing) return
        logger.d("tab cover teardown signal=${signal::class.simpleName} covered=$coveredPackage")
        onMainThread { hideOnMainThread() }
    }

    private fun showOrMoveOnMainThread(
        packageName: String,
        placement: TabCoverPlacement,
        color: Int,
        label: String
    ) {
        val existing = overlayView
        if (isShowing && existing != null) {
            val samePlacement = currentPlacement == placement
            val samePackage = coveredPackage == packageName
            if (samePlacement && samePackage) return

            // MOVE the window, never remove-and-re-add. A remove+add on every content change
            // flickers, and each add re-fires the window events that issue #41 is about.
            try {
                paint(existing, color, label)
                windowManager?.updateViewLayout(existing, layoutParamsFor(placement))
                currentPlacement = placement
                coveredPackage = packageName
                logger.d("tab cover moved package=$packageName placement=$placement")
            } catch (e: Exception) {
                logger.w("tab cover move failed package=$packageName", e)
                // A stale window that cannot be moved is worse than none: it is an opaque rectangle
                // at the old coordinates. Drop it and let the next decision re-add it cleanly.
                hideOnMainThread()
            }
            return
        }

        val ctx = serviceContext ?: run {
            logger.w("tab cover show skipped reason=no_service_context")
            return
        }

        try {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = createCoverView(ctx, color, label)
            overlayView = view
            windowManager?.addView(view, layoutParamsFor(placement))
            currentPlacement = placement
            coveredPackage = packageName
            isShowing = true
            logger.i("tab cover shown package=$packageName placement=$placement label=$label")
        } catch (e: Exception) {
            logger.w("tab cover show failed package=$packageName", e)
            resetViewState()
        }
    }

    private fun hideOnMainThread() {
        if (!isShowing) return
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            logger.w("tab cover hide failed", e)
        }
        val was = coveredPackage
        resetViewState()
        logger.i("tab cover hidden package=$was")
    }

    private fun resetViewState() {
        overlayView = null
        windowManager = null
        currentPlacement = null
        coveredPackage = null
        isShowing = false
    }

    private fun layoutParamsFor(placement: TabCoverPlacement): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            placement.width,
            placement.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE: the cover must never take input focus from the host app or dismiss its
            //   keyboard.
            // LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS: the placement comes from getBoundsInScreen, so
            //   the window has to be positioned in SCREEN coordinates, and the nav bar sits inside
            //   the system-window inset region that NO_LIMITS is what allows us to reach.
            // FLAG_NOT_TOUCHABLE is deliberately ABSENT -- see the class KDoc. Eating the tap is the
            //   feature; CounterOverlayManager sets that flag for the opposite reason.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // A solid fill, not a translucent pill: OPAQUE lets the compositor skip blending it.
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = placement.x
            y = placement.y
        }

    /**
     * The cover view.
     *
     * `AwarenessOverlayWindow.Container`, never a bare widget: the accessibility class name it
     * reports is what keeps `EventClassifier` from reading this window as Nudge coming to the
     * foreground and dropping every subsequent block for the app underneath (issue #41).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createCoverView(
        ctx: Context,
        color: Int,
        label: String
    ): AwarenessOverlayWindow.Container {
        val view = coverViewFactory(ctx)
        paint(view, color, label)
        view.isClickable = true
        // Consume the touch. Returning true here is the entire enforcement, which is why the
        // ClickableViewAccessibility lint is suppressed rather than answered with performClick():
        // there is no click behaviour to perform, and forwarding one would defeat the cover.
        view.setOnTouchListener { _, _ ->
            // Debug level only, one line. This subsystem's history is decisions that were invisible
            // in logcat, and "did the cover actually eat that tap" is unanswerable from the outside
            // otherwise. NudgeLogger.d is gated on debug builds / the debug-logging preference, so
            // a user hammering the nav bar cannot spam a release log.
            logger.d("tab cover consumed touch package=$coveredPackage")
            true
        }
        return view
    }

    /** Colour and accessibility label. Applied on create AND on a move, since either may change. */
    private fun paint(view: AwarenessOverlayWindow.Container, color: Int, label: String) {
        view.setBackgroundColor(color)
        // Nudge IS an accessibility-service app, so an unlabelled opaque region over someone's nav
        // bar is the wrong end of that. A TalkBack user sweeping the bottom nav gets told what the
        // gap is and who put it there. The feature word comes from the caller, never from here.
        view.contentDescription = describeCover(label)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Never empty: an unlabelled region is the thing this exists to avoid. */
    private fun describeCover(label: String): String =
        if (label.isBlank()) "Blocked by Nudge" else "$label blocked by Nudge"

    /**
     * Run [block] on the main thread, or inline when it is already there (or when there is no Looper
     * at all, which is only ever a JVM unit test). See [mainHandler].
     */
    private fun onMainThread(block: () -> Unit) {
        val handler = mainHandler
        if (handler == null || isOnMainThread()) {
            block()
            return
        }
        handler.post(block)
    }

    private fun isOnMainThread(): Boolean =
        runCatching { Looper.myLooper() === Looper.getMainLooper() }.getOrDefault(true)
}
