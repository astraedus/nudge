package com.astraedus.nudge.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a floating overlay that displays interaction counts on top of monitored apps.
 *
 * The overlay uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] so it can be
 * shown from the AccessibilityService without the SYSTEM_ALERT_WINDOW permission.
 * It has [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so users can interact
 * with the app behind it.
 */
@Singleton
class CounterOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: NudgeLogger
) {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var counterText: TextView? = null
    private var labelText: TextView? = null
    private var dailyText: TextView? = null
    private var timeRemainingText: TextView? = null
    private var isShowing = false
    private val density = context.resources.displayMetrics.density
    private val bgNormal = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32 * density
        setColor(Color.argb(128, 0, 0, 0))
    }
    private val bgAlert = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32 * density
        setColor(Color.argb(160, 80, 0, 0))
    }
    private var currentBgIsAlert = false

    // TYPE_ACCESSIBILITY_OVERLAY requires the service's own context for a valid window token
    private var serviceContext: Context? = null

    fun setServiceContext(ctx: Context) {
        serviceContext = ctx
        logger.d("counter overlay service context set")
    }

    fun show(label: String = "taps") {
        if (isShowing) {
            logger.d("counter overlay show skipped reason=already_visible")
            return
        }

        val ctx = serviceContext ?: run {
            logger.w("counter overlay show skipped reason=no_service_context")
            return
        }
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = createOverlayView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(overlayView, params)
            isShowing = true
            logger.i("counter overlay shown label=$label")
        } catch (e: Exception) {
            logger.w("counter overlay show failed", e)
            // Window token may be invalid if service was recently restarted
        }
    }

    fun updateCount(sessionCount: Int, dailyTotal: Int) {
        counterText?.text = sessionCount.toString()
        dailyText?.text = "today: $dailyTotal"

        // Escalating color based on session count
        val counterColor = when {
            sessionCount >= 30 -> Color.argb(220, 255, 0, 0)       // red
            sessionCount >= 20 -> Color.argb(200, 255, 69, 0)      // deep orange / red-orange
            sessionCount >= 10 -> Color.argb(180, 255, 165, 0)     // orange
            else -> Color.WHITE                                      // default
        }
        counterText?.setTextColor(counterColor)

        val container = overlayView as? LinearLayout
        val shouldAlert = sessionCount >= 30
        if (shouldAlert != currentBgIsAlert) {
            container?.background = if (shouldAlert) bgAlert else bgNormal
            currentBgIsAlert = shouldAlert
        }

        logger.d("counter overlay updated session=$sessionCount daily=$dailyTotal")
    }

    /**
     * Update the "time remaining" line on the overlay.
     * @param remainingMs time remaining in milliseconds, or null to hide the line
     * @param limitMinutes the configured daily limit (used for color escalation)
     */
    fun updateTimeRemaining(remainingMs: Long?, limitMinutes: Int?) {
        val tv = timeRemainingText ?: return
        if (remainingMs == null || limitMinutes == null || limitMinutes <= 0) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = "${formatCompactDuration(remainingMs)} left"

        // Color escalation based on % remaining
        val limitMs = limitMinutes.toLong() * 60L * 1000L
        val pct = if (limitMs > 0) remainingMs.toFloat() / limitMs else 1f
        val color = when {
            pct > 0.50f -> Color.argb(200, 100, 220, 100)  // green
            pct > 0.25f -> Color.argb(200, 255, 165, 0)    // orange
            else -> Color.argb(220, 255, 0, 0)              // red
        }
        tv.setTextColor(color)

        logger.d("time remaining updated remaining=${remainingMs}ms pct=$pct")
    }

    fun updateLabel(label: String) {
        labelText?.text = label
        logger.d("counter overlay label updated label=$label")
    }

    fun hide() {
        if (!isShowing) {
            logger.d("counter overlay hide skipped reason=not_visible")
            return
        }
        try {
            windowManager?.removeView(overlayView)
            logger.i("counter overlay hidden")
        } catch (e: Exception) {
            logger.w("counter overlay hide failed", e)
            // View may already be detached
        }
        overlayView = null
        counterText = null
        labelText = null
        dailyText = null
        timeRemainingText = null
        isShowing = false
    }

    fun isVisible(): Boolean = isShowing

    private fun createOverlayView(label: String): View {
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (32 * density).toInt(),
                (20 * density).toInt(),
                (32 * density).toInt(),
                (20 * density).toInt()
            )

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(Color.argb(128, 0, 0, 0)) // 50% black
            }
            background = bg
            alpha = 0.85f
        }

        counterText = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(counterText)

        labelText = TextView(context).apply {
            text = label
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        container.addView(labelText)

        dailyText = TextView(context).apply {
            text = "today: 0"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        container.addView(dailyText)

        timeRemainingText = TextView(context).apply {
            text = ""
            setTextColor(Color.argb(200, 100, 220, 100)) // default green
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        container.addView(timeRemainingText)

        return container
    }

    companion object {
        /**
         * Formats milliseconds into a compact duration string for the overlay.
         * Examples: "42m left", "1h 12m left", "30s left"
         */
        fun formatCompactDuration(ms: Long): String {
            if (ms <= 0) return "0s"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "${totalSeconds}s"
            }
        }
    }
}
