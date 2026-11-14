package app.cortex.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

internal class VisualTouchOverlay(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val origin = PointF()
    private val route = PathMeasure()
    private val trail = Path()
    private val location = IntArray(2)
    private val position = FloatArray(2)
    private var travel = 0f
    private var fade = 0f
    private var animation: ValueAnimator? = null
    private val dismiss = Runnable { clear() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val view = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            val distance = route.length * travel
            val onRoute = route.getPosTan(distance, position, null)
            val x = if (onRoute) position[0] else origin.x
            val y = if (onRoute) position[1] else origin.y
            val opacity = 1f - fade
            paint.style = Paint.Style.STROKE
            if (distance > 0f) {
                trail.rewind()
                route.getSegment((distance - 120 * density).coerceAtLeast(0f), distance, trail, true)
                paint.color = Color.rgb(176, 198, 240)
                paint.alpha = (47 * opacity).roundToInt()
                paint.strokeWidth = 9f * density
                canvas.drawPath(trail, paint)
                paint.color = Color.rgb(229, 238, 255)
                paint.alpha = (202 * opacity).roundToInt()
                paint.strokeWidth = 2f * density
                canvas.drawPath(trail, paint)
            }
            paint.color = Color.rgb(197, 214, 250)
            paint.alpha = (151 * opacity).roundToInt()
            paint.strokeWidth = 1.24f * density
            canvas.drawCircle(x, y, (8f + 16f * fade) * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = (244 * opacity).roundToInt()
            canvas.drawCircle(x, y, 3.25f * density, paint)
            canvas.restore()
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(path: Path, from: PointF, duration: Long, motion: Boolean) {
        clear()
        route.setPath(path, false)
        origin.set(from)
        travel = 0f
        fade = 0f
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
        }
        runCatching { manager.addView(view, params) }.onFailure { return }
        if (!motion || !ValueAnimator.areAnimatorsEnabled()) {
            view.postDelayed(dismiss, duration + 140L)
            return
        }
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration + 320L
            interpolator = LinearInterpolator()
            addUpdateListener {
                travel = (it.currentPlayTime.toFloat() / duration).coerceIn(0f, 1f)
                fade = ((it.currentPlayTime - duration) / 320f).coerceIn(0f, 1f)
                view.invalidate()
                if (it.animatedFraction >= 1f) view.post { if (animation === it) clear() }
            }
            start()
        }
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
