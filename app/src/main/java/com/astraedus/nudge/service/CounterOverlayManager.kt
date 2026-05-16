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
    @ApplicationContext private val context: Context
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
    }

    fun show(label: String = "taps") {
        if (isShowing) return

        val ctx = serviceContext ?: return
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 200 // offset from top in pixels
        }

        try {
            windowManager?.addView(overlayView, params)
            isShowing = true
        } catch (_: Exception) {
            // Window token may be invalid if service was recently restarted
        }
    }

    fun updateCount(sessionCount: Int, dailyTotal: Int) {
        counterText?.text = sessionCount.toString()
        dailyText?.text = "today: $dailyTotal"
    }

    fun updateLabel(label: String) {
        labelText?.text = label
    }

    fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(overlayView)
        } catch (_: Exception) {
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
                (20 * density).toInt(),
                (12 * density).toInt(),
                (20 * density).toInt(),
                (12 * density).toInt()
            )

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.argb(128, 0, 0, 0)) // 50% black
            }
            background = bg
            alpha = 0.85f
        }

        counterText = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(counterText)

        labelText = TextView(context).apply {
            text = label
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        container.addView(labelText)

        dailyText = TextView(context).apply {
            text = "today: 0"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        container.addView(dailyText)

        return container
    }
}
