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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CORTEX island: colorful camera-hugging overlay with a compact floating composer.
 * Tap -> open mini composer. Long press -> open the full app.
 */
class CortexIslandService : Service() {
    companion object {
        const val ACTION_CONFIG = "app.cortex.assistant.island.CONFIG"
        const val ACTION_STATUS = "app.cortex.assistant.island.STATUS"
        const val ACTION_ENSURE = "app.cortex.assistant.island.ENSURE"
        const val ACTION_DISMISS = "app.cortex.assistant.island.DISMISS"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HIDE_LANDSCAPE = "hideLandscape"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "cortex-island"
        private const val NOTIFICATION = 2810
        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, CortexIslandService::class.java).apply { this.action = action; extras() }
            try { context.startService(intent) } catch (_: Exception) { }
        }
    }

    private lateinit var manager: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var island: IslandView? = null
    private var composer: ComposerBubble? = null
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
            .setContentText("Островок включён. Нажмите, чтобы ввести команду.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(PendingIntent.getActivity(this, 32, open, pending))
            .setOngoing(true)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
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
        phase = newPhase
        message = newMessage
        ui.removeCallbacks(resetToIdle)
        when (phase) {
            "done", "error" -> ui.postDelayed(resetToIdle, 2600)
            "listening", "understood" -> ui.postDelayed(resetToIdle, 12_000)
            "executing" -> ui.postDelayed(resetToIdle, 45_000)
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeComposer()
        removeIsland()
        render()
    }

    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun shouldShow() = mode == "always" || (mode == "wake" && phase != "idle")

    private fun label() = when (phase) {
        "listening" -> if (message.isNotBlank()) message else "Слушаю…"
        "understood" -> message.ifBlank { "Понял" }
        "executing" -> message.ifBlank { "Выполняю…" }
        "done" -> "Готово"
        "error" -> message.ifBlank { "Не удалось выполнить" }
        else -> ""
    }

    private fun render() {
        if (!Settings.canDrawOverlays(this) || (hideLandscape && landscape())) { removeComposer(); removeIsland(); return }
        if (shouldShow()) {
            ui.removeCallbacks(hideIfIdle)
            if (island == null) createIsland()
            island?.setState(phase, label())
        } else if (island != null) {
            island?.setState("idle", "")
            ui.removeCallbacks(hideIfIdle)
            ui.postDelayed(hideIfIdle, 420)
        }
    }

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
        view.setOnClickListener { toggleComposer() }
        view.setOnLongClickListener { launchApp(); true }
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
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = if (cam != null) (cam.exactCenterX() - screenW / 2f).roundToInt() else 0
            y = if (cam != null) max(0, cam.top - (4 * density).roundToInt()) else (8 * density).roundToInt()
        }
        try { manager.addView(view, lp); island = view } catch (_: Exception) { island = null }
    }

    private fun toggleComposer() {
        if (composer != null) removeComposer() else showComposer()
    }

    private fun showComposer() {
        if (composer != null || island == null) return
        val density = resources.displayMetrics.density
        val width = min((resources.displayMetrics.widthPixels * 0.92f).roundToInt(), (360 * density).roundToInt())
        val bubble = ComposerBubble(this,
            onSend = { text -> submitCompose(text) },
            onClose = { removeComposer() },
            onOpenFull = { launchApp() }
        )
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            y = (cameraRect()?.bottom ?: (20 * density).roundToInt()) + (18 * density).roundToInt()
        }
        try {
            manager.addView(bubble, lp)
            composer = bubble
            bubble.focusEditor()
        } catch (_: Exception) {
            composer = null
        }
    }

    private fun submitCompose(text: String) {
        val clean = text.trim().take(600)
        if (clean.isBlank()) return
        removeComposer()
        setPhase("understood", clean.take(64))
        launchApp(clean)
    }

    private fun removeComposer() {
        composer?.hideKeyboard()
        composer?.let { runCatching { manager.removeView(it) } }
        composer = null
    }

    private fun removeIsland() {
        ui.removeCallbacks(hideIfIdle)
        removeComposer()
        island?.let { it.stop(); runCatching { manager.removeView(it) } }
        island = null
    }

    private fun launchApp(quickText: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!quickText.isNullOrBlank()) putExtra("cortex_submit_text", quickText)
        }
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "CORTEX · островок", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); removeComposer(); removeIsland(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private class ComposerBubble(
        context: Context,
        private val onSend: (String) -> Unit,
        private val onClose: () -> Unit,
        private val onOpenFull: () -> Unit
    ) : LinearLayout(context) {
        private val d = resources.displayMetrics.density
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(248, 8, 10, 16) }
        private val rect = RectF()
        private val shader = LinearGradient(0f, 0f, 600f, 0f,
            intArrayOf(0xFF5EE7FF.toInt(), 0xFF7AA2FF.toInt(), 0xFFB28DFF.toInt(), 0xFFFF7AC6.toInt(), 0xFFFFB65E.toInt()), null, Shader.TileMode.CLAMP)
        private val editor = EditText(context)

        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            setPadding((14 * d).roundToInt(), (14 * d).roundToInt(), (14 * d).roundToInt(), (14 * d).roundToInt())
            clipToPadding = false
            clipChildren = false
            elevation = 24 * d

            val titleRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                text = "CORTEX"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val hint = TextView(context).apply {
                text = "  Мини-команда"
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 12f
            }
            val close = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                setPadding((8 * d).roundToInt(), (8 * d).roundToInt(), (2 * d).roundToInt(), (8 * d).roundToInt())
                setOnClickListener { onClose() }
            }
            titleRow.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            titleRow.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            editor.apply {
                this.hint = "Напишите команду…"
                setHintTextColor(0x75FFFFFF)
                setTextColor(Color.WHITE)
                textSize = 15f
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                isSingleLine = false
                maxLines = 3
                setPadding((2 * d).roundToInt(), (8 * d).roundToInt(), (2 * d).roundToInt(), (8 * d).roundToInt())
                setOnEditorActionListener { _, actionId, event ->
                    val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                        onSend(text.toString())
                        true
                    } else false
                }
            }

            val actionRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val openFull = TextView(context).apply {
                text = "Открыть полностью"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                setOnClickListener { onOpenFull() }
            }
            val send = TextView(context).apply {
                text = "Отправить"
                setTextColor(Color.WHITE)
                setTextSize(14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding((14 * d).roundToInt(), (9 * d).roundToInt(), (14 * d).roundToInt(), (9 * d).roundToInt())
                background = null
                setOnClickListener { onSend(editor.text.toString()) }
            }
            actionRow.addView(openFull, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            actionRow.addView(send, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        override fun onDraw(canvas: Canvas) {
            val radius = 22 * d
            rect.set(1.2f * d, 1.2f * d, width.toFloat() - 1.2f * d, height.toFloat() - 1.2f * d)
            canvas.drawRoundRect(rect, radius, radius, bg)
            rim.shader = shader
            rim.alpha = 235
            canvas.drawRoundRect(rect, radius, radius, rim)
        }

        fun focusEditor() {
            post {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        fun hideKeyboard() {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private class IslandView(context: Context, camW: Int, camH: Int) : View(context) {
        private val d = resources.displayMetrics.density
        private val baseH = if (camH > 0) camH + 8 * d else 30 * d
        private val baseW = if (camW > 0) camW + 18 * d else 84 * d
        private val rowH = 44 * d
        private val pad = 16 * d
        private val glyphZone = 38 * d
        private val maxW = min(resources.displayMetrics.widthPixels * 0.92f, 338 * d)
        private val minOpenW = 205 * d

        private var phase = "idle"
        private var glyphPhase = "idle"
        private var text = ""
        private var stateStart = SystemClock.uptimeMillis()

        private var w = baseW; private var h = baseH
        private var vw = 0f; private var vh = 0f
        private var tw = baseW; private var th = baseH
        private var rim = 0.28f; private var rimTarget = 0.28f
        private var angle = 0f
        private var last = 0L
        private var running = false

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val softBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF07090E.toInt() }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2.3f * d
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13.5f * d
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val shader = SweepGradient(0f, 0f,
            intArrayOf(0xFF5EE7FF.toInt(), 0xFF7AA2FF.toInt(), 0xFFB28DFF.toInt(), 0xFFFF7AC6.toInt(), 0xFFFFB65E.toInt(), 0xFF5EE7FF.toInt()), null)
        private val matrix = Matrix()
        private val rect = RectF()
        private val arc = RectF()
        private val path = Path()
        private val seg = Path()
        private val measure = PathMeasure()
        private val barColors = intArrayOf(0xFF5EE7FF.toInt(), 0xFF7FB2FF.toInt(), 0xFFA78BFA.toInt(), 0xFFFF7AC6.toInt(), 0xFFFFB65E.toInt())
        private val frame = Choreographer.FrameCallback { tick(it) }

        init { rimPaint.shader = shader; innerRim.shader = shader }

        fun setState(newPhase: String, newText: String) {
            if (newPhase != phase) stateStart = SystemClock.uptimeMillis()
            phase = newPhase
            if (newPhase != "idle") { glyphPhase = newPhase; text = newText }
            if (newPhase == "idle") {
                tw = baseW; th = baseH; rimTarget = 0.26f
            } else {
                tw = (pad * 2 + glyphZone + textPaint.measureText(text)).coerceIn(min(minOpenW, maxW), maxW)
                th = baseH + rowH; rimTarget = 1f
            }
            contentDescription = if (newPhase == "idle") "CORTEX" else "CORTEX $text"
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
            vw += ((tw - w) * 380f - vw * 24f) * dt; w += vw * dt
            vh += ((th - h) * 380f - vh * 24f) * dt; h += vh * dt
            rim += (rimTarget - rim) * min(1f, dt * 6f)
            angle = (angle + dt * (if (phase == "idle") 30f else 165f)) % 360f
            val settled = abs(tw - w) < 0.5f && abs(th - h) < 0.5f && abs(vw) < 2f && abs(vh) < 2f
            if (settled) { w = tw; h = th; vw = 0f; vh = 0f }
            requestLayout(); invalidate()
            if (phase != "idle" || !settled || abs(rim - rimTarget) > 0.01f) Choreographer.getInstance().postFrameCallback(frame)
            else running = false
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
            c.drawRoundRect(rect.insetCopy(1.4f * d), radius, radius, softBg)

            matrix.setRotate(angle); matrix.postTranslate(cw / 2f, ch / 2f)
            shader.setLocalMatrix(matrix)
            val a = rim.coerceIn(0f, 1f)
            rimPaint.strokeWidth = 10 * d; rimPaint.alpha = (48 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)
            rimPaint.strokeWidth = 5.4f * d; rimPaint.alpha = (98 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)
            rimPaint.strokeWidth = 2.2f * d; rimPaint.alpha = (255 * a).toInt(); c.drawRoundRect(rect, radius, radius, rimPaint)
            innerRim.strokeWidth = 1.2f * d; innerRim.alpha = (140 * a).toInt();
            c.drawRoundRect(RectF(rect.left + 4.8f * d, rect.top + 4.8f * d, rect.right - 4.8f * d, rect.bottom - 4.8f * d), radius, radius, innerRim)

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

        private fun RectF.insetCopy(value: Float): RectF = RectF(left + value, top + value, right - value, bottom - value)

        private fun drawGlyph(c: Canvas, cx: Float, cy: Float, t: Float) {
            val now = SystemClock.uptimeMillis()
            val since = (now - stateStart) / 1000f
            glyph.alpha = (255 * t).toInt()
            when (glyphPhase) {
                "listening" -> for (i in 0 until 5) {
                    val amp = 0.34f + 0.66f * abs(sin(now / 170f + i * 1.15f))
                    val bar = (5f + 15f * amp) * d * t
                    val x = cx + (i - 2) * 5.6f * d
                    glyph.color = barColors[i]
                    c.drawLine(x, cy - bar / 2f, x, cy + bar / 2f, glyph)
                }
                "understood", "executing" -> {
                    glyph.color = 0xFF7DD3FC.toInt()
                    arc.set(cx - 9 * d, cy - 9 * d, cx + 9 * d, cy + 9 * d)
                    c.drawArc(arc, (now / 3.6f) % 360f, 110f + 70f * sin(now / 260f), false, glyph)
                }
                "done" -> {
                    glyph.color = 0xFF4ADE80.toInt()
                    c.drawCircle(cx, cy, 10 * d, glyph)
                    path.reset()
                    path.moveTo(cx - 4.5f * d, cy + 0.5f * d); path.lineTo(cx - 1.5f * d, cy + 4f * d); path.lineTo(cx + 5f * d, cy - 3.5f * d)
                    measure.setPath(path, false)
                    seg.reset(); measure.getSegment(0f, measure.length * (since / 0.35f).coerceIn(0f, 1f), seg, true)
                    c.drawPath(seg, glyph)
                }
                "error" -> {
                    glyph.color = 0xFFF87171.toInt()
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
