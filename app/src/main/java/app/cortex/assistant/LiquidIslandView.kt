package app.cortex.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

internal class LiquidIslandView(
    context: Context,
    val conversation: IslandConversationPanel,
    private val onOpen: () -> Unit,
    private val onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onStop: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val body = RectF()
    private val contentClip = Rect()
    private val expandedClip = android.graphics.Path()
    private val box = Rect()
    private val location = IntArray(2)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val signal = IslandSignal(density)
    private var placement: IslandPlacement? = null
    private var animator: ValueAnimator? = null
    private var expansion = 0f
    private var disappearance = 0f
    private var motion = true
    private var phase = "idle"
    private var level = 0f
    private var levelAt = 0L
    private var reactionAt = 0L
    private var audioFrameAt = 0L
    private var press = false
    private var activeStop = false
    private var powerDown = false
    var haptics = true

    private val stop = View(context).apply {
        visibility = INVISIBLE
        isFocusable = true
        isSoundEffectsEnabled = false
        contentDescription = "Остановить задачу CORTEX"
        setOnClickListener { onStop() }
    }

    init {
        setWillNotDraw(false)
        isSoundEffectsEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        setOnClickListener { onOpen() }
        setOnLongClickListener { onOpenApp(); true }
        addView(conversation, LayoutParams(0, 0))
        addView(stop, LayoutParams(dp(39), dp(40)))
        setContents()
    }

    fun place(value: IslandPlacement, frame: Rect) {
        if (value === placement && frame == box) return
        placement = value
        box.set(frame)
        val panel = value.panel
        val old = conversation.layoutParams
        if (old.width != panel.width().roundToInt() || old.height != panel.height().roundToInt()) {
            conversation.layoutParams = LayoutParams(panel.width().roundToInt(), panel.height().roundToInt())
        }
        requestLayout()
        invalidate()
    }

    fun phase(value: String, message: String, canStop: Boolean) {
        if (phase != value) reactionAt = SystemClock.uptimeMillis()
        phase = value
        activeStop = canStop
        val description = if (message.isBlank()) "Островок CORTEX. Ввести команду." else "CORTEX. $message"
        if (contentDescription != description) contentDescription = description
        setContents()
    }

    fun audioLevel(value: Float) {
        val now = SystemClock.uptimeMillis()
        level = mix(level * exp((levelAt - now) / 240f), value.coerceIn(0f, 1f), .48f)
        levelAt = now
        conversation.audioLevel(level)
        if (now - audioFrameAt > 32L) {
            audioFrameAt = now
            invalidate()
        }
    }

    fun windowContains(point: PointF): Boolean {
        if (!isAttachedToWindow) return false
        getLocationOnScreen(location)
        return point.x >= location[0] && point.x < location[0] + width &&
            point.y >= location[1] && point.y < location[1] + height
    }

    fun reduceMotion(reduced: Boolean) {
        val enabled = !reduced && ValueAnimator.areAnimatorsEnabled()
        if (motion == enabled) return
        motion = enabled
        conversation.animateSignal(motion)
        invalidate()
    }

    fun expand(open: Boolean, done: () -> Unit) {
        val start = expansion
        val faded = disappearance
        val end = if (open) 1f else 0f
        val duration = ((if (open) 490 else 370) * abs(start - end)).toLong().coerceAtLeast(110L)
        powerDown = false
        animate(duration, if (open) OPEN_CURVE else CLOSE_CURVE, {
            expansion = mix(start, end, it)
            disappearance = mix(faded, 0f, it)
        }, done)
    }

    fun disappear(power: Boolean, done: () -> Unit) {
        val start = expansion
        val faded = disappearance
        powerDown = power
        animate(if (power) 560L else 330L, CLOSE_CURVE, { t ->
            expansion = start * (1f - ease(.04f, .76f, t))
            disappearance = mix(faded, 1f, t)
        }, done)
    }

    fun cancelAnimation() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    private fun animate(duration: Long, curve: PathInterpolator, step: (Float) -> Unit, done: () -> Unit) {
        cancelAnimation()
        if (!motion) {
            step(1f)
            setContents()
            done()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = curve
            addUpdateListener {
                step(it.animatedValue as Float)
                setContents()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    done()
                }
            })
            start()
        }
    }

    private fun setContents() {
        val appear = ease(.45f, .98f, expansion) * (1f - ease(0f, .29f, disappearance))
        conversation.alpha = appear
        conversation.reveal(appear)
        conversation.visibility = if (appear > .03f) VISIBLE else INVISIBLE
        val interactive = appear > .9f && disappearance == 0f
        conversation.clipBounds = if (interactive) null else clipConversation()
        conversation.importantForAccessibility =
            if (interactive) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        stop.visibility = if (activeStop && expansion < .14f && disappearance == 0f) VISIBLE else INVISIBLE
        importantForAccessibility = if (interactive) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        invalidate()
    }

    private fun clipConversation(): Rect? {
        val geometry = placement ?: return null
        val panel = geometry.panel
        val width = panel.width().roundToInt()
        val height = panel.height().roundToInt()
        contentClip.set(
            (mix(geometry.compact.left, panel.left, expansion) - panel.left).roundToInt().coerceIn(0, width),
            (mix(geometry.compact.top, panel.top, expansion) - panel.top).roundToInt().coerceIn(0, height),
            (mix(geometry.compact.right, panel.right, expansion) - panel.left).roundToInt().coerceIn(0, width),
            (mix(geometry.compact.bottom, panel.bottom, expansion) - panel.top).roundToInt().coerceIn(0, height)
        )
        return contentClip
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val geometry = placement ?: return
        val panel = geometry.panel
        val x = (panel.left - box.left).roundToInt()
        val y = (panel.top - box.top).roundToInt()
        conversation.layout(x, y, x + panel.width().roundToInt(), y + panel.height().roundToInt())
        val compact = geometry.compact
        stop.layout((compact.right - box.left - dp(39)).roundToInt(), (compact.top - box.top).roundToInt(),
            (compact.right - box.left).roundToInt(), (compact.bottom - box.top).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = placement ?: return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - reactionAt
        val moving = motion && isShown && windowVisibility == VISIBLE
        val time = if (moving) (now % 240_000L) / 1000f else 0f
        val compact = geometry.compact
        val panel = geometry.panel
        val t = expansion
        val fade = 1f - ease(.25f, 1f, disappearance)
        val scale = (1f - (if (powerDown) .87f else .19f) * ease(.31f, 1f, disappearance)) *
            (if (press && moving) .965f else 1f)
        val x = geometry.anchorX - box.left
        val y = geometry.anchorY - box.top
        body.set(
            x + (mix(compact.left, panel.left, t) - geometry.anchorX) * scale,
            y + (mix(compact.top, panel.top, t) - geometry.anchorY) * scale,
            x + (mix(compact.right, panel.right, t) - geometry.anchorX) * scale,
            y + (mix(compact.bottom, panel.bottom, t) - geometry.anchorY) * scale
        )
        val radius = minOf(mix(20f, 22f, t) * density * scale, body.height() / 2f)
        ink.style = Paint.Style.FILL
        ink.color = if (t > .30f) 0xFF0A0A0A.toInt() else Color.BLACK
        ink.alpha = (255 * fade).roundToInt()
        canvas.drawRoundRect(body, radius, radius, ink)
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = .9f * density
        ink.color = Color.WHITE
        val pulse = if (moving && phase == "done" && elapsed in 0..550) (1f - elapsed / 550f) * 115 else 0f
        ink.alpha = ((mix(69f, 151f, t) + pulse) * fade).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(body, radius, radius, ink)

        val compactAlpha = (1f - ease(.05f, .50f, t)) * (1f - ease(0f, .76f, disappearance))
        if (compactAlpha > .02f) {
            val cy = body.centerY()
            signal.mark(canvas, body.left + body.width() * .27f, cy, compactAlpha)
            val sx = body.left + body.width() * .65f
            when (phase) {
                "done" -> signal.check(canvas, sx, cy, compactAlpha, if (moving) (elapsed / 250f).coerceIn(0f, 1f) else 1f)
                "listening", "executing" -> signal.wave(canvas, if (activeStop) sx - dp(8) else sx, cy,
                    phase, time, level * exp((levelAt - now) / 240f), compactAlpha)
                else -> signal.dot(canvas, sx, cy, compactAlpha)
            }
            if (activeStop) {
                val stopX = body.right - dp(17)
                val r = 3.5f * density
                ink.style = Paint.Style.FILL
                ink.alpha = (230 * compactAlpha).roundToInt()
                canvas.drawRoundRect(stopX - r, cy - r, stopX + r, cy + r, density, density, ink)
            }
        }
        if (moving && (phase == "listening" || phase == "executing" || phase == "done" && elapsed < 550L)) {
            postInvalidateDelayed(33L)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (expansion < 1f || disappearance > 0f) {
            val radius = minOf(21 * density, body.height() / 2f)
            expandedClip.rewind()
            expandedClip.addRoundRect(body, radius, radius, android.graphics.Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(expandedClip)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        } else super.dispatchDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (expansion > .20f) onDismiss()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                if (disappearance > 0f || expansion > .25f || !body.contains(event.x, event.y)) return false
                press = true
                if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                press = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (expansion > .8f && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(from: Float, to: Float, value: Float): Float {
        val x = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private companion object {
        val OPEN_CURVE = PathInterpolator(.18f, .82f, .22f, 1f)
        val CLOSE_CURVE = PathInterpolator(.32f, 0f, .29f, 1f)
    }
}
