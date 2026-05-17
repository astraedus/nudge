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

@Singleton
class CounterOverlayManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: NudgeLogger
) : CounterOverlayManagerApi {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var counterText: TextView? = null
    private var labelText: TextView? = null
    private var dailyText: TextView? = null
    @Volatile
    private var isShowing = false
    private val density = appContext.resources.displayMetrics.density
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

    // TYPE_ACCESSIBILITY_OVERLAY needs the service context for both the window token
    // AND view creation. Using ApplicationContext for views causes BadTokenException.
    @Volatile
    private var serviceContext: Context? = null

    fun setServiceContext(ctx: Context) {
        serviceContext = ctx
        logger.d("counter overlay service context set")
    }

    fun clearServiceContext() {
        hide()
        serviceContext = null
    }

    override fun show(label: String) {
        if (isShowing) return

        val ctx = serviceContext ?: run {
            logger.w("counter overlay show skipped reason=no_service_context")
            return
        }

        try {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = createOverlayView(ctx, label)

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

            windowManager?.addView(overlayView, params)
            isShowing = true
            logger.i("counter overlay shown label=$label")
        } catch (e: Exception) {
            logger.w("counter overlay show failed", e)
            resetViewState()
        }
    }

    override fun updateCount(sessionCount: Int, dailyTotal: Int) {
        if (!isShowing) return
        counterText?.text = sessionCount.toString()
        dailyText?.text = "today: $dailyTotal"

        val counterColor = when {
            sessionCount >= 30 -> Color.argb(220, 255, 0, 0)
            sessionCount >= 20 -> Color.argb(200, 255, 69, 0)
            sessionCount >= 10 -> Color.argb(180, 255, 165, 0)
            else -> Color.WHITE
        }
        counterText?.setTextColor(counterColor)

        val container = overlayView as? LinearLayout
        val shouldAlert = sessionCount >= 30
        if (shouldAlert != currentBgIsAlert) {
            container?.background = if (shouldAlert) bgAlert else bgNormal
            currentBgIsAlert = shouldAlert
        }
    }

    fun updateLabel(label: String) {
        labelText?.text = label
    }

    override fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            logger.w("counter overlay hide failed", e)
        }
        resetViewState()
        logger.i("counter overlay hidden")
    }

    override fun isVisible(): Boolean = isShowing

    private fun resetViewState() {
        overlayView = null
        counterText = null
        labelText = null
        dailyText = null
        windowManager = null
        isShowing = false
        currentBgIsAlert = false
    }

    private fun createOverlayView(ctx: Context, label: String): View {
        val d = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * d).toInt(), (20 * d).toInt(), (32 * d).toInt(), (20 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * d
                setColor(Color.argb(128, 0, 0, 0))
            }
            alpha = 0.85f
        }

        counterText = TextView(ctx).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(counterText)

        labelText = TextView(ctx).apply {
            text = label
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        container.addView(labelText)

        dailyText = TextView(ctx).apply {
            text = "today: 0"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        container.addView(dailyText)

        return container
    }

    companion object {
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
