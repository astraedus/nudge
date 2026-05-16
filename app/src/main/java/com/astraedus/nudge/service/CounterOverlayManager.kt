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
    private var isShowing = false

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

        // Tint background red at 30+ scrolls
        val container = overlayView as? LinearLayout
        if (sessionCount >= 30) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * (context.resources.displayMetrics.density)
                setColor(Color.argb(160, 80, 0, 0))
            }
            container?.background = bg
        } else {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * (context.resources.displayMetrics.density)
                setColor(Color.argb(128, 0, 0, 0))
            }
            container?.background = bg
        }

        logger.d("counter overlay updated session=$sessionCount daily=$dailyTotal")
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

        return container
    }
}
