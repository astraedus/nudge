package com.astraedus.nudge.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.astraedus.nudge.domain.windows.FloatingCoverEffect
import com.astraedus.nudge.domain.windows.WindowBounds
import com.astraedus.nudge.util.NudgeLogger

/**
 * The cover over a blocked app's FLOATING window: one touch-consuming `TYPE_ACCESSIBILITY_OVERLAY`
 * laid exactly over the window's bounds ([#71](https://github.com/astraedus/nudge/issues/71)).
 *
 * The decisions (where, when to move, when to come off, when to press BACK) are all
 * [com.astraedus.nudge.domain.windows.FloatingWindowCover]'s. This class does window work only, and
 * copies [TabCoverOverlayManager]'s approach on purpose, including the two places it differs from
 * the awareness pills next door:
 *
 *  - **No `FLAG_NOT_TOUCHABLE`.** Eating the touch IS the block: an accessibility overlay is stacked
 *    above freeform windows, which is the one thing the fullscreen block overlay cannot be.
 *  - **`FLAG_NOT_FOCUSABLE`.** Input focus stays on the floating window underneath, which is what
 *    lets a BACK press (the automatic one, or the cover's own "Close" button) reach the blocked app
 *    and close it. HOME does not dismiss a floating window.
 *
 * Every view wears the [AwarenessOverlayWindow] identity: our own overlay's events must classify as
 * an awareness overlay, never as Nudge coming to the foreground (issue #41), or the cover would
 * drop the very blocks it exists to enforce. `AwarenessOverlayContractTest` reads this file.
 *
 * It counts nothing. A touch the cover eats is not a confrontation, exactly as for the tab cover.
 */
class FloatingWindowCoverManager(
    private val logger: NudgeLogger,
    private val onCloseRequested: () -> Unit
) {

    @Volatile private var serviceContext: Context? = null
    private var windowManager: WindowManager? = null
    private var view: View? = null

    @Volatile private var coveredPackage: String? = null
    @Volatile private var shownBounds: WindowBounds? = null

    /** Window ids we have already pressed BACK on (at most one press per floating window). */
    private val backPressed: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val mainHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    /**
     * How the cover view is built. Production has one answer; a JVM test substitutes a stub,
     * because every `android.view.View` setter is a throwing stub in this module's unit tests (the
     * same seam [TabCoverOverlayManager.coverViewFactory] exists for).
     */
    @VisibleForTesting
    internal var coverViewFactory: (Context, () -> Unit) -> View = ::buildCoverView

    fun setServiceContext(ctx: Context) {
        serviceContext = ctx
    }

    fun clearServiceContext() {
        hide()
        serviceContext = null
    }

    fun coveredPackage(): String? = coveredPackage

    fun shownBounds(): WindowBounds? = shownBounds

    /** Ids already pressed, for [com.astraedus.nudge.domain.windows.FloatingWindowCover.backTarget]. */
    fun backPressedWindows(): Set<Int> = backPressed

    /** Record a BACK press on [windowId] so it is never pressed twice. */
    fun noteBackPressed(windowId: Int) {
        backPressed += windowId
    }

    fun apply(effect: FloatingCoverEffect) {
        when (effect) {
            is FloatingCoverEffect.None -> return
            is FloatingCoverEffect.Hide -> hide()
            is FloatingCoverEffect.Show -> onMainThread { showOrMove(effect.packageName, effect.bounds) }
        }
    }

    /** Remove the cover if one is up. Idempotent. */
    fun hide() {
        onMainThread { hideOnMainThread() }
    }

    /** Remove the cover only if it is over [packageName] (the block for it just ended). */
    fun hideIfCovering(packageName: String) {
        if (coveredPackage == packageName) hide()
    }

    private fun showOrMove(packageName: String, bounds: WindowBounds) {
        val existing = view
        if (existing != null) {
            if (coveredPackage == packageName && shownBounds == bounds) return
            try {
                windowManager?.updateViewLayout(existing, layoutParamsFor(bounds))
                coveredPackage = packageName
                shownBounds = bounds
                logger.d("floating cover moved package=$packageName bounds=$bounds")
            } catch (e: Exception) {
                logger.w("floating cover move failed package=$packageName", e)
                hideOnMainThread()
            }
            return
        }
        val ctx = serviceContext ?: run {
            logger.w("floating cover show skipped reason=no_service_context")
            return
        }
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val built = coverViewFactory(ctx) { onCloseRequested() }
            wm.addView(built, layoutParamsFor(bounds))
            windowManager = wm
            view = built
            coveredPackage = packageName
            shownBounds = bounds
            logger.i("floating cover shown package=$packageName bounds=$bounds")
        } catch (e: Exception) {
            logger.w("floating cover show failed package=$packageName", e)
            reset()
        }
    }

    private fun hideOnMainThread() {
        val existing = view ?: return
        try {
            windowManager?.removeView(existing)
        } catch (e: Exception) {
            logger.w("floating cover hide failed", e)
        }
        val was = coveredPackage
        reset()
        logger.i("floating cover hidden package=$was")
    }

    private fun reset() {
        view = null
        windowManager = null
        coveredPackage = null
        shownBounds = null
    }

    /**
     * Field assignments rather than the five-argument constructor, for the reason
     * [TabCoverOverlayManager] documents: in the JVM test target an android constructor body is a
     * no-op stub, so values passed through it read back as zero and cannot be asserted.
     */
    @VisibleForTesting
    internal fun layoutParamsFor(bounds: WindowBounds): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            width = bounds.width
            height = bounds.height
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // NOT_FOCUSABLE keeps input focus on the floating window, so BACK reaches it.
            // LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS: bounds are SCREEN coordinates, and a floating
            //   window can be dragged partly off-screen, where a clamped cover would leave a strip.
            // FLAG_NOT_TOUCHABLE is deliberately ABSENT: eating the touch is the block.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

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

    private companion object {
        /** Near-opaque ink: the app underneath must not be readable, the edge of it can be. */
        const val SCRIM = 0xF2111318.toInt()
        const val TEXT = 0xFFF3F1EC.toInt()
        const val MUTED = 0xFFB9B6AE.toInt()
        const val BUTTON = 0xFF2B2F36.toInt()

        /**
         * The production cover: a scrim that eats every touch, a line saying who put it there, and
         * a "Close window" button that presses BACK (the floating window keeps input focus, so the
         * press reaches it). The OEM's own close handle is usually drawn INSIDE the window's
         * bounds, under this cover, so the button is the user's way out.
         */
        @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
        fun buildCoverView(ctx: Context, onClose: () -> Unit): View {
            fun dp(v: Float): Int = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
            ).toInt()

            val root = AwarenessOverlayWindow.Container(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.setBackgroundColor(SCRIM)
            root.setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
            root.isClickable = true
            // Consume every touch that reaches the scrim. The button below is a child, so it gets
            // its own tap first.
            root.setOnTouchListener { _, _ -> true }
            root.contentDescription = "Blocked by Nudge"
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

            val title = AwarenessOverlayWindow.Label(ctx)
            title.text = "Blocked by Nudge"
            title.setTextColor(TEXT)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            title.gravity = Gravity.CENTER
            root.addView(title)

            val body = AwarenessOverlayWindow.Label(ctx)
            body.text = "This app is blocked right now, floating window included."
            body.setTextColor(MUTED)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            body.gravity = Gravity.CENTER
            body.setPadding(0, dp(8f), 0, dp(20f))
            root.addView(body)

            val close = AwarenessOverlayWindow.Label(ctx)
            close.text = "Close window"
            close.setTextColor(TEXT)
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            close.gravity = Gravity.CENTER
            close.setBackgroundColor(BUTTON)
            close.setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            close.minHeight = dp(48f)
            close.isClickable = true
            close.setOnClickListener { onClose() }
            root.addView(close)
            return root
        }
    }
}
