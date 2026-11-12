package app.cortex.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CORTEX island: a black capsule that hugs the front camera (Dynamic-Island style).
 * Idle: tiny capsule around the camera. Active: it springs open downwards with a glowing,
 * rotating gradient rim and a state glyph (waveform / spinner / check / cross) plus a label.
 * Real TYPE_APPLICATION_OVERLAY window; needs the "display over other apps" permission.
 */
class CortexIslandService : Service() {
    companion object {
        const val ACTION_CONFIG = "app.cortex.assistant.island.CONFIG"
        const val ACTION_STATUS = "app.cortex.assistant.island.STATUS"
        const val ACTION_ENSURE = "app.cortex.assistant.island.ENSURE"
        const val ACTION_DISMISS = "app.cortex.assistant.island.DISMISS"
        const val EXTRA_MODE = "mode"             // always | wake | off
        const val EXTRA_HIDE_LANDSCAPE = "hideLandscape"
        const val EXTRA_PHASE = "phase"           // idle | listening | understood | executing | done | error
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "cortex-island"
        private const val NOTIFICATION = 2810
        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, CortexIslandService::class.java).apply { this.action = action; extras() }
            try { context.startService(intent) } catch (_: Exception) { /* UI shows "unavailable" */ }
        }
    }

    private lateinit var manager: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var island: IslandView? = null
    private var mode = "off"
    private var hideLandscape = true
    private var phase = "idle"
    private var message = ""

    private val resetToIdle = Runnable { phase = "idle"; message = ""; render() }
    private val hideIfIdle = Runnable { if (!shouldShow()) removeIsland() }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadPrefs()
        ensureChannel()
        val open = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CORTEX")
            .setContentText("Островок включён. Управление — в настройках CORTEX.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(PendingIntent.getActivity(this, 32, open, pending))
            .setOngoing(true)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION, notification)
        }.isSuccess
        if (!started) { stopSelf(); return }
        render()
    }

    private fun loadPrefs() {
        val store = getSharedPreferences("cortex-display", MODE_PRIVATE)
        mode = store.getString("mode", "off") ?: "off"
        hideLandscape = store.getBoolean("hideLandscape", true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIG -> {
                val newMode = intent.getStringExtra(EXTRA_MODE) ?: mode
                if (newMode in listOf("always", "wake", "off")) mode = newMode
                hideLandscape = intent.getBooleanExtra(EXTRA_HIDE_LANDSCAPE, true)
                getSharedPreferences("cortex-display", MODE_PRIVATE).edit()
                    .putString("mode", mode).putBoolean("hideLandscape", hideLandscape).apply()
                if (mode == "off") { stopSelf(); return START_NOT_STICKY }
                setPhase("idle", "")
            }
            ACTION_ENSURE -> {
                loadPrefs()
                if (mode == "off") { stopSelf(); return START_NOT_STICKY }
                render()
            }
            ACTION_STATUS -> {
                loadPrefs()
                if (mode == "off") { stopSelf(); return START_NOT_STICKY }
                setPhase(intent.getStringExtra(EXTRA_PHASE) ?: "idle", intent.getStringExtra(EXTRA_MESSAGE).orEmpty().take(64))
            }
            ACTION_DISMISS -> setPhase("idle", "")
        }
        return if (mode == "off") START_NOT_STICKY else START_STICKY
    }

    private fun setPhase(newPhase: String, newMessage: String) {
        phase = newPhase; message = newMessage
        ui.removeCallbacks(resetToIdle)
        when (phase) {
            "done", "error" -> ui.postDelayed(resetToIdle, 2400)
            "listening", "understood" -> ui.postDelayed(resetToIdle, 12_000)
            "executing" -> ui.postDelayed(resetToIdle, 45_000)
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeIsland()   // cut-out position changes with rotation
        render()
    }

    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun shouldShow() = mode == "always" || (mode == "wake" && phase != "idle")

    private fun label() = when (phase) {
        "listening" -> "Слушаю…"
        "understood" -> message.ifBlank { "Понял" }
        "executing" -> message.ifBlank { "Выполняю…" }
        "done" -> "Готово"
        "error" -> "Не удалось выполнить"
        else -> ""
    }

    private fun render() {
        if (!Settings.canDrawOverlays(this) || (hideLandscape && landscape())) { removeIsland(); return }
        if (shouldShow()) {
            ui.removeCallbacks(hideIfIdle)
            if (island == null) createIsland()
            island?.setState(phase, label())
        } else if (island != null) {
            island?.setState("idle", "")
            ui.removeCallbacks(hideIfIdle)
            ui.postDelayed(hideIfIdle, 420)   // let the collapse animation finish
        }
    }

    /** Front-camera cut-out at the top edge (Android 11+). null on devices without one. */
    private fun cameraRect(): Rect? {
        if (Build.VERSION.SDK_INT < 30) return null
        val cutout = runCatching { manager.currentWindowMetrics.windowInsets.displayCutout }.getOrNull() ?: return null
        val top = cutout.boundingRectTop
        return if (top.width() > 0 && top.height() > 0) top else null
    }

    private fun createIsland() {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val cam = cameraRect()
        val view = IslandView(this, cam?.width() ?: 0, cam?.height() ?: 0)
        view.setOnClickListener { launchApp() }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = if (cam != null) (cam.exactCenterX() - screenW / 2f).roundToInt() else 0
            y = if (cam != null) max(0, cam.top - (4 * density).roundToInt()) else (8 * density).roundToInt()
        }
        try { manager.addView(view, lp); island = view } catch (_: Exception) { island = null }
    }

    private fun removeIsland() {
        ui.removeCallbacks(hideIfIdle)
        island?.let { it.stop(); runCatching { manager.removeView(it) } }
        island = null
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "CORTEX · островок", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); removeIsland(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------------------------------
    private class IslandView(context: Context, camW: Int, camH: Int) : View(context) {
        private val d = resources.displayMetrics.density
        private val baseH = if (camH > 0) camH + 8 * d else 30 * d     // collapsed: hugs the camera
        private val baseW = if (camW > 0) camW + 18 * d else 84 * d
        private val rowH = 44 * d                                      // extra height of the content row
        private val pad = 16 * d
        private val glyphZone = 38 * d
        private val maxW = min(resources.displayMetrics.widthPixels * 0.92f, 330 * d)
        private val minOpenW = 200 * d

        private var phase = "idle"
        private var glyphPhase = "idle"
        private var text = ""
        private var stateStart = SystemClock.uptimeMillis()

        private var w = baseW; private var h = baseH
        private var vw = 0f; private var vh = 0f
        private var tw = baseW; private var th = baseH
        private var rim = 0.25f; private var rimTarget = 0.25f
        private var angle = 0f
        private var last = 0L
        private var running = false

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2.3f * d
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13.5f * d
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val shader = SweepGradient(0f, 0f,
            intArrayOf(0xFF5EE7FF.toInt(), 0xFFA78BFA.toInt(), 0xFFFF7AC6.toInt(), 0xFF5EE7FF.toInt()), null)
        private val matrix = Matrix()
        private val rect = RectF()
        private val arc = RectF()
        private val path = Path()
        private val seg = Path()
        private val measure = PathMeasure()
        private val barColors = intArrayOf(0xFF5EE7FF.toInt(), 0xFF7FB2FF.toInt(), 0xFFA78BFA.toInt(), 0xFFD07CF0.toInt(), 0xFFFF7AC6.toInt())
        private val frame = Choreographer.FrameCallback { tick(it) }

        init { rimPaint.shader = shader }

        fun setState(newPhase: String, newText: String) {
            if (newPhase != phase) stateStart = SystemClock.uptimeMillis()
            phase = newPhase
            if (newPhase != "idle") { glyphPhase = newPhase; text = newText }
            if (newPhase == "idle") {
                tw = baseW; th = baseH; rimTarget = 0.22f
            } else {
                tw = (pad * 2 + glyphZone + textPaint.measureText(text)).coerceIn(min(minOpenW, maxW), maxW)
                th = baseH + rowH; rimTarget = 1f
            }
            contentDescription = "CORTEX " + text
            start()
        }

        private fun start() {
            if (running) return
            running = true; last = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }

        fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(frame) }

        private fun tick(nanos: Long) {
            if (!running) return
            val dt = if (last == 0L) 0.016f else ((nanos - last) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
            last = nanos
            // Underdamped springs -> a small, pleasant overshoot when opening.
            vw += ((tw - w) * 380f - vw * 24f) * dt; w += vw * dt
            vh += ((th - h) * 380f - vh * 24f) * dt; h += vh * dt
            rim += (rimTarget - rim) * min(1f, dt * 6f)
            angle = (angle + dt * (if (phase == "idle") 24f else 140f)) % 360f
            val settled = abs(tw - w) < 0.5f && abs(th - h) < 0.5f && abs(vw) < 2f && abs(vh) < 2f
            if (settled) { w = tw; h = th; vw = 0f; vh = 0f }
            if (measuredWidth != w.roundToInt() || measuredHeight != max(h, baseH * 0.8f).roundToInt()) requestLayout()
            invalidate()
            if (phase != "idle" || !settled || abs(rim - rimTarget) > 0.01f) {
                Choreographer.getInstance().postFrameCallback(frame)
            } else running = false
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(max(1, w.roundToInt()), max(1, (max(h, baseH * 0.8f)).roundToInt()))
        }

        override fun onDraw(c: Canvas) {
            val cw = width.toFloat(); val ch = height.toFloat()
            val radius = min(ch / 2f, 28 * d)
            val inset = 1.2f * d
            rect.set(inset, inset, cw - inset, ch - inset)
            c.drawRoundRect(rect, radius, radius, bg)

            matrix.setRotate(angle); matrix.postTranslate(cw / 2f, ch / 2f)
            shader.setLocalMatrix(matrix)
            val a = rim.coerceIn(0f, 1f)
            rimPaint.strokeWidth = 8 * d; rimPaint.alpha = (24 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)
            rimPaint.strokeWidth = 4 * d; rimPaint.alpha = (60 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)
            rimPaint.strokeWidth = 1.6f * d; rimPaint.alpha = (255 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)

            val t = ((ch - baseH) / rowH).coerceIn(0f, 1f)
            if (t < 0.05f) return
            val cy = baseH + rowH / 2f - 2 * d + (1f - t) * 6 * d
            drawGlyph(c, pad + 14 * d, cy, t)
            val textX = pad + glyphZone
            val avail = max(0f, cw - textX - pad)
            val shown = TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END)
            textPaint.alpha = (255 * t * t).toInt()
            val ty = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            c.drawText(shown, 0, shown.length, textX, ty, textPaint)
        }

        private fun drawGlyph(c: Canvas, cx: Float, cy: Float, t: Float) {
            val now = SystemClock.uptimeMillis()
            val since = (now - stateStart) / 1000f
            glyph.alpha = (255 * t).toInt()
            when (glyphPhase) {
                "listening" -> for (i in 0 until 5) {
                    val amp = 0.3f + 0.7f * abs(sin(now / 170f + i * 1.15f))
                    val bar = (5f + 15f * amp) * d * t
                    val x = cx + (i - 2) * 5.6f * d
                    glyph.color = barColors[i]; glyph.alpha = (255 * t).toInt()
                    c.drawLine(x, cy - bar / 2f, x, cy + bar / 2f, glyph)
                }
                "understood", "executing" -> {
                    glyph.color = 0xFF7DD3FC.toInt(); glyph.alpha = (255 * t).toInt()
                    arc.set(cx - 9 * d, cy - 9 * d, cx + 9 * d, cy + 9 * d)
                    c.drawArc(arc, (now / 3.6f) % 360f, 110f + 70f * sin(now / 260f), false, glyph)
                }
                "done" -> {
                    glyph.color = 0xFF4ADE80.toInt(); glyph.alpha = (255 * t).toInt()
                    c.drawCircle(cx, cy, 10 * d, glyph)
                    path.reset()
                    path.moveTo(cx - 4.5f * d, cy + 0.5f * d); path.lineTo(cx - 1.5f * d, cy + 4f * d); path.lineTo(cx + 5f * d, cy - 3.5f * d)
                    measure.setPath(path, false)
                    seg.reset()
                    measure.getSegment(0f, measure.length * (since / 0.35f).coerceIn(0f, 1f), seg, true)
                    c.drawPath(seg, glyph)
                }
                "error" -> {
                    glyph.color = 0xFFF87171.toInt(); glyph.alpha = (255 * t).toInt()
                    val shake = sin(since * 42f) * 4f * d * max(0f, 1f - since / 0.5f)
                    c.save(); c.translate(shake, 0f)
                    c.drawCircle(cx, cy, 10 * d, glyph)
                    c.drawLine(cx - 4 * d, cy - 4 * d, cx + 4 * d, cy + 4 * d, glyph)
                    c.drawLine(cx + 4 * d, cy - 4 * d, cx - 4 * d, cy + 4 * d, glyph)
                    c.restore()
                }
            }
        }

        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    }
}
