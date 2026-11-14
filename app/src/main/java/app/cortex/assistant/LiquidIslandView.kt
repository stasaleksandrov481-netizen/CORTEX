package app.cortex.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal class LiquidIslandView(
    context: Context,
    val conversation: IslandConversationPanel,
    private val onOpen: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onOpenApp: () -> Unit,
    onStop: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var placement: IslandPlacement? = null
    private val location = IntArray(2)
    private val compact = RectF()
    private val expanded = RectF()
    private val fromCompact = RectF()
    private val fromExpanded = RectF()
    private val body = RectF()
    private val surface = Path()
    private val collar = Path()
    private val cutout = Path()
    private val neck = Path()
    private var lastScreen: IslandScreen? = null
    private val matrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val sheen = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0xFF333333.toInt(), 0xFF191919.toInt(), 0xFF0D0D0D.toInt(), 0xFF171717.toInt()),
        floatArrayOf(0f, .27f, .74f, 1f), Shader.TileMode.CLAMP)
    private val rim = SweepGradient(0f, 0f, intArrayOf(
        0xFFD4D4D4.toInt(), 0xFF8E8E8E.toInt(), 0xFFEBEBEB.toInt(), 0xFF838383.toInt(),
        0xFFB4B4B4.toInt(), 0xFFECECEC.toInt(), 0xFFD4D4D4.toInt()
    ), floatArrayOf(0f, .16f, .34f, .53f, .68f, .84f, 1f))
    private val reflection = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0x82FFFFFF.toInt(), 0x15FFFFFF, 0x00FFFFFF, 0x30FFFFFF),
        floatArrayOf(0f, .22f, .70f, 1f), Shader.TileMode.CLAMP)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val signal = IslandSignal(density)
    private var audio = 0f
    private var audioAt = 0L
    private var label = ""
    val hasStatus get() = label.isNotEmpty()
    private var textLayout: StaticLayout? = null
    private var lastLabelSpace = 0
    private var stoppable = false
    private var phase = "idle"
    private var press = 0f
    private var progress = 0f
    private var impulseAt = 0L
    private var impulseStrength = 0f
    private var layoutAt = 0L
    private var animation: ValueAnimator? = null
    private var motion = true
    private val stop = TextView(context).apply {
        contentDescription = "Остановить задачу CORTEX"
        isFocusable = true
        visibility = INVISIBLE
        setOnClickListener { onStop() }
    }
    var haptics = true

    init {
        setWillNotDraw(false)
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Островок CORTEX. Ввести команду."
        setOnClickListener { onOpen() }
        setOnLongClickListener { onOpenApp(); true }
        addView(conversation, LayoutParams(0, 0))
        conversation.visibility = INVISIBLE
        conversation.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        conversation.onInteraction = ::react
        addView(stop, LayoutParams((46 * density).roundToInt(), (48 * density).roundToInt()))
    }

    fun place(value: IslandPlacement, deform: Boolean = false) {
        val previous = placement
        val now = SystemClock.uptimeMillis()
        val changed = previous != null && (value.compact != previous.compact || value.panel != previous.panel)
        if (changed && deform && motion) {
            visibleRects(now)
            fromCompact.set(compact)
            fromExpanded.set(expanded)
            layoutAt = now
        } else if (changed) {
            layoutAt = 0L
        }
        placement = value
        val panel = value.panel
        val next = LayoutParams(panel.width().roundToInt(), panel.height().roundToInt())
        val old = conversation.layoutParams as LayoutParams
        if (next.width != old.width || next.height != old.height) {
            conversation.layoutParams = next
        }
        if (lastScreen !== value.screen) {
            lastScreen = value.screen
            prepareCamera(value)
        }
        if (changed) requestLayout()
        invalidate()
    }

    fun phase(value: String, message: String, canStop: Boolean) {
        if (phase == value && label == message && stoppable == canStop) return
        if (phase != value && value != "idle") react(.55f)
        phase = value
        label = message
        stoppable = canStop
        stop.visibility = if (canStop) VISIBLE else INVISIBLE
        lastLabelSpace = 0
        contentDescription = if (label.isBlank()) "Островок CORTEX. Ввести команду." else "CORTEX. $label"
        invalidate()
    }

    fun audioLevel(level: Float) {
        if (phase != "listening") return
        val now = SystemClock.uptimeMillis()
        audio = mix(audioLevelAt(now), level, .46f)
        audioAt = now
        conversation.audioLevel(audio)
        invalidate()
    }

    private fun audioLevelAt(now: Long) = audio * exp((audioAt - now) / 240f)

    fun windowContains(point: PointF): Boolean {
        if (!isAttachedToWindow) return false
        getLocationOnScreen(location)
        return point.x >= location[0] && point.x < location[0] + width &&
            point.y >= location[1] && point.y < location[1] + height
    }

    fun expand(open: Boolean, animate: Boolean, done: () -> Unit) {
        animation?.removeAllListeners()
        animation?.cancel()
        val target = if (open) 1f else 0f
        conversation.importantForAccessibility = if (open) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            progress = target
            showContent()
            done()
            return
        }
        animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = ((if (open) 540 else 420) * abs(target - progress)).toLong().coerceAtLeast(110)
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; showContent() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { done() }
            })
            start()
        }
    }

    fun reduceMotion(reduced: Boolean) {
        val enabled = !reduced && ValueAnimator.areAnimatorsEnabled()
        if (motion == enabled) return
        motion = enabled
        conversation.animateSignal(enabled)
        invalidate()
    }

    private fun react(strength: Float) {
        if (!motion) return
        impulseAt = SystemClock.uptimeMillis()
        impulseStrength = strength
        invalidate()
    }

    private fun showContent() {
        val alpha = smooth(.38f, .87f, progress)
        conversation.visibility = if (alpha > .025f) VISIBLE else INVISIBLE
        conversation.alpha = alpha
        conversation.translationY = (1f - alpha) * 8 * density
        conversation.scaleX = .949f + .051f * alpha
        conversation.scaleY = .965f + .035f * alpha
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (progress > .20f) onDismiss()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                if (progress > .25f) return false
                if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                react(.8f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> invalidate()
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (progress > .8f && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun prepareCamera(geometry: IslandPlacement) {
        cutout.rewind()
        collar.rewind()
        val physical = Path()
        val clip = Path()
        val padded = Path()
        for (camera in geometry.screen.cutouts) {
            val bounds = RectF(camera.bounds)
            clip.rewind()
            clip.addRect(bounds, Path.Direction.CW)
            physical.rewind()
            camera.contour?.let { physical.set(it) }
            if (physical.isEmpty || !physical.op(clip, Path.Op.INTERSECT) || physical.isEmpty) physical.set(clip)
            edge.shader = null
            edge.strokeWidth = 1.5f * density
            padded.rewind()
            edge.getFillPath(physical, padded)
            cutout.op(physical, Path.Op.UNION)
            cutout.op(padded, Path.Op.UNION)
        }
        val hood = geometry.anchor?.collar ?: return
        val radius = minOf(hood.width(), hood.height()) * .47f
        collar.addRoundRect(hood, radius, radius, Path.Direction.CW)
    }

    private fun visibleRects(now: Long) {
        val target = placement ?: return
        val fraction = if (layoutAt > 0 && motion) smooth(0f, 1f, (now - layoutAt) / GEOMETRY_MS.toFloat()) else 1f
        interpolate(fromCompact, target.compact, fraction, compact)
        interpolate(fromExpanded, target.panel, fraction, expanded)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(location)
        val geometry = placement ?: return
        val panel = geometry.panel
        val x = (panel.left - location[0]).roundToInt()
        val y = (panel.top - location[1]).roundToInt()
        conversation.layout(x, y, x + conversation.measuredWidth, y + conversation.measuredHeight)
        val stopRight = (geometry.compact.right - location[0]).roundToInt()
        val stopTop = (geometry.compact.centerY() - location[1] - stop.measuredHeight / 2f).roundToInt()
        stop.layout(stopRight - stop.measuredWidth, stopTop, stopRight, stopTop + stop.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = placement ?: return
        val now = SystemClock.uptimeMillis()
        val animate = motion && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        val seconds = if (animate) (now % 720_000L) / 1000f else 0f
        val p = progress
        visibleRects(now)
        press = if (animate) mix(press, if (isPressed) 1f else 0f, .35f) else 0f
        val tick = ((now - impulseAt) / 1000f).coerceAtLeast(0f)
        val impulse = if (animate && tick < .60f) {
            impulseStrength * exp(-tick * 6.9f) * sin(tick * 26f)
        } else 0f
        val pinch = if (animate) sin(PI * (p / .29f).coerceIn(0f, 1f)).toFloat() else 0f
        val spill = if (animate) sin(PI * smooth(.18f, .95f, p)).toFloat() else 0f
        val rebound = if (animate) sin(2 * PI * smooth(.62f, 1f, p)).toFloat() * (1 - p) else 0f
        val idle = if (animate) sin(seconds * 2f) * .40f else 0f
        val wide = smooth(.08f, .77f, p)
        val down = smooth(.23f, .93f, p)
        val flex = (8.4f * spill + 9f * rebound - 5.3f * pinch - 3.8f * press + impulse * 8.0f + idle) * density
        body.set(
            mix(compact.left, expanded.left, wide) - flex,
            mix(compact.top, expanded.top, down) + (pinch * 3.6f + press * 1.4f - impulse * 1.8f) * density,
            mix(compact.right, expanded.right, wide) + flex,
            mix(compact.bottom, expanded.bottom, down) + (spill * 12f + rebound * 21f - pinch * 3.2f + impulse * 5.5f + idle) * density
        )
        val lift = (pinch * 2.6f + spill * 4.8f + impulse * 4.6f + idle) * density
        drawContour(lift, p, spill)
        geometry.anchor?.takeIf { IslandPlacement.near(it, body, 76 * density) }?.let { attach(it, spill, impulse) }

        canvas.save()
        getLocationOnScreen(location)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        if (!cutout.isEmpty) canvas.clipOutPath(cutout)
        matrix.setScale(1f, body.height().coerceAtLeast(1f))
        matrix.postTranslate(0f, body.top)
        sheen.setLocalMatrix(matrix)
        fill.shader = sheen
        fill.alpha = 255
        canvas.drawPath(surface, fill)
        fill.shader = null

        matrix.setRotate(210f + seconds * (if (phase == "idle") 18f else 48f) + p * 94f)
        matrix.postTranslate(body.centerX(), body.centerY())
        rim.setLocalMatrix(matrix)
        edge.shader = rim
        edge.strokeWidth = (5.8f + 2.5f * spill) * density
        edge.alpha = (20 + spill * 22 + abs(impulse) * 26).roundToInt()
        canvas.drawPath(surface, edge)
        edge.strokeWidth = (1.28f + .35f * spill) * density
        edge.alpha = (215 + 30 * spill).roundToInt()
        canvas.drawPath(surface, edge)
        if (!collar.isEmpty && !cutout.isEmpty) {
            canvas.save()
            canvas.clipPath(surface)
            edge.strokeWidth = .84f * density
            edge.alpha = 140
            canvas.drawPath(cutout, edge)
            canvas.restore()
        }
        matrix.setScale(1f, body.height().coerceAtLeast(1f))
        matrix.postTranslate(0f, body.top)
        reflection.setLocalMatrix(matrix)
        edge.shader = reflection
        edge.strokeWidth = .62f * density
        edge.alpha = 225
        canvas.drawPath(surface, edge)
        edge.shader = null

        val alpha = 1f - smooth(.06f, .42f, p)
        if (alpha > 0f) drawSignal(canvas, seconds, alpha, audioLevelAt(now))
        canvas.restore()
        if (animate && animation?.isRunning != true) {
            val active = phase == "listening" || phase == "executing" || tick < .6f || isPressed || layoutAt > now - GEOMETRY_MS
            postInvalidateDelayed(if (active) 24 else 64)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (surface.isEmpty) return
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        canvas.clipPath(surface)
        if (!cutout.isEmpty) canvas.clipOutPath(cutout)
        canvas.translate(location[0].toFloat(), location[1].toFloat())
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private fun drawContour(lift: Float, open: Float, spill: Float) {
        val l = body.left
        val r = body.right
        val t = body.top
        val b = body.bottom
        val w = body.width().coerceAtLeast(1f)
        val h = body.height().coerceAtLeast(1f)
        val topRadius = minOf(w * .44f, h * .49f, (19f + 20f * open) * density)
        val bottomRadius = minOf(w * .46f, h * .52f, (19f + 22f * open + spill * 6f) * density)
        surface.rewind()
        surface.moveTo(l + topRadius, t)
        surface.cubicTo(l + w * .37f, t - lift * .7f, r - w * .39f, t + lift * .2f, r - topRadius, t)
        surface.cubicTo(r - topRadius * .37f, t, r, t + topRadius * .46f, r, t + topRadius)
        surface.cubicTo(r + lift * .44f, t + h * .51f, r + lift * .52f, b - bottomRadius * .62f, r, b - bottomRadius)
        surface.cubicTo(r, b - bottomRadius * .36f, r - bottomRadius * .44f, b, r - bottomRadius, b)
        surface.cubicTo(r - w * .38f, b + lift * 1.05f, l + w * .31f, b + lift * .60f, l + bottomRadius, b)
        surface.cubicTo(l + bottomRadius * .39f, b, l, b - bottomRadius * .38f, l, b - bottomRadius)
        surface.cubicTo(l - lift * .30f, b - h * .40f, l - lift * .62f, t + topRadius * .9f, l, t + topRadius)
        surface.cubicTo(l, t + topRadius * .37f, l + topRadius * .42f, t, l + topRadius, t)
        surface.close()
    }

    private fun attach(anchor: IslandAnchor, spill: Float, impulse: Float) {
        val x = anchor.x
        val y = anchor.y
        val cx = x.coerceIn(body.left + body.width() * .22f, body.right - body.width() * .22f)
        val cy = y.coerceIn(body.top + body.height() * .28f, body.bottom - body.height() * .28f)
        val dx = cx - x
        val dy = cy - y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / length
        val ny = dx / length
        val start = (8.3f + impulse * 2.8f) * density
        val waist = (5.2f + spill * 3.1f) * density
        val end = (15.5f + spill * 7.4f) * density
        neck.rewind()
        neck.moveTo(x + nx * start, y + ny * start)
        neck.cubicTo(x + dx * .32f + nx * waist, y + dy * .32f + ny * waist,
            x + dx * .65f + nx * waist, y + dy * .65f + ny * waist, cx + nx * end, cy + ny * end)
        neck.lineTo(cx - nx * end, cy - ny * end)
        neck.cubicTo(x + dx * .65f - nx * waist, y + dy * .65f - ny * waist,
            x + dx * .30f - nx * waist, y + dy * .30f - ny * waist, x - nx * start, y - ny * start)
        neck.close()
        surface.op(neck, Path.Op.UNION)
        surface.op(collar, Path.Op.UNION)
    }

    private fun drawSignal(canvas: Canvas, time: Float, alpha: Float, level: Float) {
        val withLabel = label.isNotEmpty() && body.width() > 95 * density
        val centerX = if (withLabel) body.left + 25 * density else body.centerX() - 17 * density
        val centerY = body.centerY()
        signal.face(canvas, centerX, centerY, phase, time, alpha)
        if (!withLabel) {
            signal.wave(canvas, centerX + 32 * density, centerY, phase, time, level, alpha)
            return
        }
        val space = (body.width() - (if (stoppable) 94 else 61) * density).roundToInt().coerceAtLeast(1)
        if (space != lastLabelSpace) {
            lastLabelSpace = space
            textLayout = StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, space)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        }
        labelPaint.alpha = (alpha * 239).roundToInt()
        textLayout?.let { layout ->
            canvas.save()
            canvas.translate(body.left + 46 * density, centerY - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        if (stoppable) {
            val x = body.right - 22 * density
            val radius = 4.2f * density
            fill.color = 0xFFD9D9D9.toInt()
            fill.alpha = (alpha * 29).roundToInt()
            canvas.drawCircle(x, centerY, 14 * density, fill)
            fill.alpha = (alpha * 244).roundToInt()
            canvas.drawRoundRect(x - radius, centerY - radius, x + radius, centerY + radius, density, density, fill)
        }
    }

    override fun onDetachedFromWindow() {
        animation?.removeAllListeners()
        animation?.cancel()
        super.onDetachedFromWindow()
    }

    private fun smooth(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: Float, b: Float, at: Float) = a + (b - a) * at

    private fun interpolate(a: RectF, b: RectF, at: Float, out: RectF) =
        out.set(mix(a.left, b.left, at), mix(a.top, b.top, at), mix(a.right, b.right, at), mix(a.bottom, b.bottom, at))

    companion object {
        const val GEOMETRY_MS = 310L
    }
}
