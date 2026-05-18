package com.astraedus.nudge.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeRemainingOverlayManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: NudgeLogger
) : TimeRemainingOverlayManagerApi {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var timeText: TextView? = null
    @Volatile
    private var isShowing = false
    private val density = appContext.resources.displayMetrics.density

    @Volatile
    private var serviceContext: Context? = null

    fun setServiceContext(ctx: Context) {
        serviceContext = ctx
    }

    fun clearServiceContext() {
        hide()
        serviceContext = null
    }

    override fun show() {
        if (isShowing) return
        val ctx = serviceContext ?: return

        try {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = createOverlayView(ctx)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (16 * density).toInt()
                y = (48 * density).toInt()
            }

            windowManager?.addView(overlayView, params)
            isShowing = true
            logger.i("time remaining overlay shown")
        } catch (e: Exception) {
            logger.w("time remaining overlay show failed", e)
            resetViewState()
        }
    }

    override fun updateTimeRemaining(remainingMs: Long?, limitMinutes: Int?) {
        if (remainingMs == null || limitMinutes == null || limitMinutes <= 0 || remainingMs <= 0) {
            hide()
            return
        }
        if (!isShowing) show()

        val tv = timeText ?: return
        tv.text = "${CounterOverlayManager.formatCompactDuration(remainingMs)} left"

        val limitMs = limitMinutes.toLong() * 60L * 1000L
        val pct = if (limitMs > 0) remainingMs.toFloat() / limitMs else 1f
        val textColor = when {
            pct > 0.50f -> Color.argb(240, 100, 220, 100)   // Green
            pct > 0.25f -> Color.argb(240, 255, 165, 0)     // Orange
            else -> Color.argb(255, 255, 80, 80)             // Red
        }
        tv.setTextColor(textColor)

        // Background gets more opaque as time runs low
        val bg = overlayView?.background as? GradientDrawable
        val bgAlpha = when {
            pct > 0.50f -> 140
            pct > 0.25f -> 170
            else -> 200
        }
        bg?.setColor(Color.argb(bgAlpha, 0, 0, 0))
    }

    override fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            logger.w("time remaining overlay hide failed", e)
        }
        resetViewState()
    }

    override fun isVisible(): Boolean = isShowing

    private fun resetViewState() {
        overlayView = null
        timeText = null
        windowManager = null
        isShowing = false
    }

    private fun createOverlayView(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density

        val tv = TextView(ctx).apply {
            text = ""
            setTextColor(Color.argb(240, 100, 220, 100))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * d
                setColor(Color.argb(140, 0, 0, 0))
            }
            alpha = 0.9f
        }
        timeText = tv
        return tv
    }
}
