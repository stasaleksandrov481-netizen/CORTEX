package app.cortex.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

private fun overlayParams(width: Int, height: Int, extra: Int = 0) = WindowManager.LayoutParams(
    width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extra, PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP or Gravity.LEFT }

internal class VisualProgressOverlay(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 2
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(6), 0)
    }
    private val stop = TextView(context).apply {
        text = "■"
        setTextColor(0xFFFACCCF.toInt())
        textSize = 16f
        gravity = Gravity.CENTER
        contentDescription = "Остановить задачу CORTEX"
        setOnClickListener { CortexActionState.stop() }
    }
    private val view = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF0181B21.toInt())
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0x6657DDE7)
        }
        addView(label, LinearLayout.LayoutParams(0, -1, 1f))
        addView(stop, LinearLayout.LayoutParams(dp(47), -1))
    }
    private val dismiss = Runnable { hide() }
    private var lastMessage = ""
    private var lastPhase = ""
    private var lower = false

    fun show(message: String, phase: String) {
        ui.removeCallbacks(dismiss)
        if (message != lastMessage) {
            label.text = message
            lastMessage = message
        }
        stop.visibility = if (phase == "executing") View.VISIBLE else View.GONE
        if (CortexRuntime.appVisible) {
            hide()
            return
        }
        val params = placement()
        if (!view.isAttachedToWindow) {
            runCatching { manager.addView(view, params) }.onSuccess {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    view.alpha = .25f
                    view.animate().alpha(1f).setDuration(160).start()
                }
            }
        } else if (phase != lastPhase) runCatching { manager.updateViewLayout(view, params) }
        lastPhase = phase
        if (phase != "executing") ui.postDelayed(dismiss, if (phase == "done") 4_500L else 7_000L)
    }

    fun contains(point: PointF): Boolean {
        if (!view.isAttachedToWindow) return false
        val location = IntArray(2).also(view::getLocationOnScreen)
        return point.x >= location[0] && point.x < location[0] + view.width &&
            point.y >= location[1] && point.y < location[1] + view.height
    }

    fun moveAwayFrom(point: PointF): Boolean {
        if (!contains(point)) return false
        lower = !lower
        return runCatching { manager.updateViewLayout(view, placement()) }.isSuccess
    }

    private fun placement() = overlayParams(dp(260), dp(46)).apply {
        val metrics = context.resources.displayMetrics
        width = width.coerceAtMost((metrics.widthPixels - dp(24)).coerceAtLeast(dp(120)))
        x = (metrics.widthPixels - width) / 2
        y = if (lower) (metrics.heightPixels - dp(106)).coerceAtLeast(dp(46)) else dp(46)
    }

    fun hide() {
        ui.removeCallbacks(dismiss)
        if (view.isAttachedToWindow) runCatching { manager.removeViewImmediate(view) }
        lower = false
    }

    override fun close() = hide()
    private fun dp(value: Int) = (value * density).roundToInt()
}

internal class VisualTouchOverlay(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var start = PointF()
    private var end = PointF()
    private var fraction = 0f
    private var travel = 0f
    private var animation: ValueAnimator? = null
    private val dismiss = Runnable { clear() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val view = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val x = start.x + (end.x - start.x) * travel
            val y = start.y + (end.y - start.y) * travel
            val opacity = ((1f - fraction) * 245).toInt().coerceIn(0, 255)
            paint.color = Color.argb(opacity, 139, 240, 246)
            paint.strokeWidth = 2.1f * density
            paint.style = Paint.Style.STROKE
            if (start != end) canvas.drawLine(start.x, start.y, x, y, paint)
            canvas.drawCircle(x, y, (9f + 15f * fraction) * density, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 3.5f * density, paint)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(from: PointF, to: PointF, duration: Long) {
        clear()
        start = PointF(from.x, from.y)
        end = PointF(to.x, to.y)
        fraction = 0f
        travel = 0f
        val params = overlayParams(-1, -1, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        runCatching { manager.addView(view, params) }.onFailure { return }
        val location = IntArray(2).also(view::getLocationOnScreen)
        start.offset(-location[0].toFloat(), -location[1].toFloat())
        end.offset(-location[0].toFloat(), -location[1].toFloat())
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration + 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                travel = (it.currentPlayTime.toFloat() / duration).coerceIn(0f, 1f)
                view.invalidate()
                if (it.animatedFraction >= 1f) view.post { if (animation === it) clear() }
            }
            start()
        }
        if (!ValueAnimator.areAnimatorsEnabled()) view.postDelayed(dismiss, duration + 160L)
    }

    fun clear() {
        view.removeCallbacks(dismiss)
        animation?.removeAllUpdateListeners()
        animation?.cancel()
        animation = null
        if (view.isAttachedToWindow) runCatching { manager.removeViewImmediate(view) }
    }

    override fun close() = clear()
}
