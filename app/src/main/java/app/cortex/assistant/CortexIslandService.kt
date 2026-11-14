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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

class CortexIslandService : Service(), DisplayManager.DisplayListener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var overlayContext: Context
    private lateinit var manager: WindowManager
    private lateinit var displays: DisplayManager
    private val preferences by lazy { getSharedPreferences("cortex-display", MODE_PRIVATE) }
    private val assistantDelegate = lazy { IslandAssistant(this) }
    private val assistant by assistantDelegate
    private var island: LiquidIslandView? = null
    private var window: WindowManager.LayoutParams? = null
    private var legacyProbe: View? = null
    private var legacyBars = Rect()
    private var legacyCutout: DisplayCutout? = null
    private var screen: IslandScreen? = null
    private var mode = "off"
    private var phase = "idle"
    private var message = ""
    private var draft = ""
    private var reply = ""
    private var opening = false
    private var moving = false
    private var busy = false
    private var wakeExpanded = false
    private var hiddenByVoice = false
    private var touchThrough = false
    private val transitionFrame = Rect()
    private var task: CortexActionState.Status? = null
    private val actionBusy get() = task?.phase == "executing"
    private val actionStatus: (CortexActionState.Status) -> Unit = ::updateTask
    private val visibilityChanged: () -> Unit = { render() }
    private val wakeLevel: (Float) -> Unit = { island?.audioLevel(it) }
    private val passThrough: (PointF?) -> Boolean = ::passTouchesThrough

    private val settleLayout = Runnable {
        transitionFrame.setEmpty()
        island?.let { placeIsland(it) }
    }
    private val clearTask = Runnable { task = null; render() }
    private val returnToIdle = Runnable { updateStatus("idle", "") }
    private var repaintPending = false
    private val repaintAnswer = Runnable {
        repaintPending = false
        if (busy) render()
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        CortexActionState.listener = actionStatus
        CortexRuntime.islandVisibility = visibilityChanged
        CortexRuntime.wakeLevel = wakeLevel
        CortexRuntime.islandTouchPassThrough = passThrough
        displays = getSystemService(DisplayManager::class.java)
        val primary = displays.getDisplay(Display.DEFAULT_DISPLAY)
        overlayContext = if (Build.VERSION.SDK_INT >= 30 && primary != null) {
            runCatching { createDisplayContext(primary).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) }
                .getOrDefault(this)
        } else this
        manager = overlayContext.getSystemService(WindowManager::class.java)
        displays.registerDisplayListener(this, ui)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "CORTEX · островок", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 32, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CORTEX")
            .setContentText("Островок доступен поверх приложений")
            .setSmallIcon(R.drawable.ic_cortex)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
        }.onFailure { stopSelf() }
        CortexActionState.status?.takeIf {
            it.phase == "executing" || SystemClock.uptimeMillis() - it.at < RESULT_MS
        }?.let(::updateTask)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIG) {
            val requested = intent.getStringExtra(EXTRA_MODE)
            if (requested in setOf("always", "wake", "off")) {
                preferences.edit().putString("mode", requested)
                    .putBoolean(EXTRA_HIDE_LANDSCAPE, intent.getBooleanExtra(EXTRA_HIDE_LANDSCAPE, true)).apply()
                if (intent.getBooleanExtra(EXTRA_RESUME, false)) hiddenByVoice = false
            }
        }
        mode = preferences.getString("mode", "off") ?: "off"
        if (mode == "off" || preferences.getBoolean(WAKE_PAUSED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_WAKE -> {
                hiddenByVoice = false
                if (!busy && !actionBusy) {
                    task = null
                    ui.removeCallbacks(clearTask)
                    wakeExpanded = !opening
                    updateStatus("listening", "Слушаю команду…")
                    setExpanded(true)
                } else render()
            }
            ACTION_WAKE_CLOSED -> if (phase == "listening" && !busy) {
                if (wakeExpanded) setExpanded(false)
                wakeExpanded = false
                updateStatus("idle", "")
            }
            ACTION_SUBMIT -> {
                val text = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                if (text.trim().matches(STOP_COMMAND)) stopAnswer()
                else if (text.isNotBlank() && !hiddenByVoice && canShow() && !busy && !actionBusy) {
                    submit(text, fromVoice = true)
                }
            }
            else -> render()
        }
        return START_STICKY
    }

    private fun canShow() = Settings.canDrawOverlays(this) &&
        (!preferences.getBoolean(EXTRA_HIDE_LANDSCAPE, true) ||
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)

    private fun updateTask(status: CortexActionState.Status) {
        task = status
        ui.removeCallbacks(clearTask)
        if (actionBusy) {
            setExpanded(false)
        } else {
            val remaining = RESULT_MS - (SystemClock.uptimeMillis() - status.at)
            ui.postDelayed(clearTask, remaining.coerceAtLeast(0))
        }
        render()
    }

    private fun hideTemporarily() {
        hiddenByVoice = true
        if (!busy) updateStatus("idle", "") else render()
    }

    private fun updateStatus(state: String, text: String) {
        ui.removeCallbacks(returnToIdle)
        phase = state
        message = text
        if (!busy) {
            val delay = when (state) {
                "done", "error", "stopped" -> if (reply.isNotBlank()) 12_000L else RESULT_MS
                "listening" -> 0L
                "understood" -> 12_000L
                "executing" -> 0L
                else -> 0L
            }
            if (delay > 0) ui.postDelayed(returnToIdle, delay)
        }
        render()
    }

    private fun render() {
        if (mode == "off" || hiddenByVoice || CortexRuntime.appVisible || !canShow() ||
            (mode == "wake" && phase == "idle" && task == null && !opening && !moving)) {
            removeIsland()
            return
        }
        if (Build.VERSION.SDK_INT in 26..28 && legacyProbe == null) watchLegacyInsets()
        val geometryChanged = screen == null
        val nextScreen = screen ?: measureScreen().also { screen = it }
        val visible = island ?: createIsland(nextScreen) ?: return
        val state = task?.phase ?: phase
        val label = task?.message ?: when (phase) {
            "listening" -> message.ifBlank { "Слушаю…" }
            "executing" -> message.ifBlank { "Думаю…" }
            "done" -> if (reply.isNotBlank()) "Ответ готов" else "Готово"
            "error" -> if (reply.isNotBlank()) "Открыть ответ" else message.ifBlank { "Не удалось выполнить" }
            "stopped" -> message.ifBlank { "Остановлено" }
            "understood" -> message
            else -> ""
        }
        val wasStatus = visible.hasStatus
        visible.phase(state, label, actionBusy)
        visible.haptics = preferences.getBoolean("haptics", true)
        visible.reduceMotion(preferences.getBoolean("reduceMotion", false))
        visible.conversation.status(state, label)
        visible.conversation.busy(busy || actionBusy)
        val answerResized = visible.conversation.answer(reply)
        if (geometryChanged || wasStatus != (label.isNotEmpty()) || answerResized) {
            placeIsland(visible, deform = !geometryChanged && !moving)
        }
    }

    private fun createIsland(currentScreen: IslandScreen): LiquidIslandView? {
        val panel = IslandConversationPanel(overlayContext, { submit(it) }, ::stopAnswer,
            { setExpanded(false) }, ::openApplication)
        panel.draft(draft)
        val view = LiquidIslandView(overlayContext, panel, { setExpanded(true) },
            { setExpanded(false) }, ::openApplication, ::stopAnswer)
        screen = currentScreen
        val params = layoutParams()
        window = params
        placeIsland(view, update = false)
        return runCatching { manager.addView(view, params); view }.onSuccess { island = it }
            .onFailure { window = null }.getOrNull()
    }

    private fun setExpanded(open: Boolean) {
        if (open && actionBusy) return
        val view = island ?: return
        if (open == opening) return
        opening = open
        moving = true
        if (!open) view.conversation.hideKeyboard()
        placeIsland(view)
        view.postOnAnimation {
            if (island !== view || opening != open) return@postOnAnimation
            view.expand(open, !preferences.getBoolean("reduceMotion", false)) {
                if (island === view && opening == open) {
                    moving = false
                    placeIsland(view)
                    if (!open) render()
                }
            }
        }
    }

    private fun placeIsland(view: LiquidIslandView, update: Boolean = true, deform: Boolean = false) {
        val viewport = screen ?: return
        val lp = window ?: return
        val d = view.resources.displayMetrics.density
        val safe = viewport.safeArea(d)
        val panelWidth = minOf(safe.width(), 352 * d).roundToInt().coerceAtLeast(1)
        view.conversation.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(safe.height().roundToInt().coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        val placement = IslandPlacement(viewport, d, view.hasStatus, panelWidth, view.conversation.measuredHeight)
        val box = Rect(placement.frame(opening, moving))
        val previous = Rect(lp.x + viewport.bounds.left, lp.y + viewport.bounds.top,
            lp.x + lp.width + viewport.bounds.left, lp.y + lp.height + viewport.bounds.top)
        val transition = deform && !previous.isEmpty &&
            !preferences.getBoolean("reduceMotion", false) && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (transition) {
            transitionFrame.union(previous)
            ui.removeCallbacks(settleLayout)
            ui.postDelayed(settleLayout, LiquidIslandView.GEOMETRY_MS)
        }
        if (moving) box.union(previous)
        if (!transitionFrame.isEmpty) box.union(transitionFrame)
        val flags = windowFlags(opening && !actionBusy)
        if (box == previous && flags == lp.flags) {
            view.place(placement, transition)
            return
        }
        lp.width = box.width().coerceAtLeast(1)
        lp.height = box.height().coerceAtLeast(1)
        lp.x = box.left - viewport.bounds.left
        lp.y = box.top - viewport.bounds.top
        lp.flags = flags
        if (update && view.isAttachedToWindow) runCatching { manager.updateViewLayout(view, lp) }
        view.place(placement, transition)
    }

    private fun submit(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim().take(2400)
        if (handleControl(this, text) != null) return
        if (busy || actionBusy || text.isEmpty()) return
        if (fromVoice) island?.conversation?.hideKeyboard()
        busy = true
        task = null
        ui.removeCallbacks(clearTask)
        reply = ""
        updateStatus("executing", "Думаю…")
        setExpanded(true)
        val streamed = StringBuilder()
        assistant.send(text, preferences.getString("profile", "auto") ?: "auto",
            beforeScreen = { setExpanded(false) },
            toolsAllowed = { preferences.getBoolean("phoneActions", true) && Settings.canDrawOverlays(this) },
            progress = { updateStatus("executing", it) },
            delta = { chunk ->
                if (streamed.length < 20_000) {
                    streamed.append(chunk.take(20_000 - streamed.length))
                    reply = streamed.toString()
                    message = "Отвечаю…"
                    if (!repaintPending) {
                        repaintPending = true
                        ui.postDelayed(repaintAnswer, 65)
                    }
                }
            },
            finished = { answer, ok ->
                busy = false
                reply = answer.trim()
                updateStatus(if (ok) "done" else "error", "")
            })
    }

    private fun stopAnswer() {
        if (!busy) { if (actionBusy) CortexActionState.stop(); return }
        assistant.cancel()
        busy = false
        if (reply.isBlank()) reply = "Ответ остановлен."
        updateStatus("stopped", "Остановлено")
    }

    private fun openApplication() {
        setExpanded(false)
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun layoutParams(touchable: Boolean = true): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (touchable) windowFlags(false) else windowFlags(false) or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    @Suppress("DEPRECATION")
    private fun windowFlags(focus: Boolean): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (Build.VERSION.SDK_INT < 30) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0) or
            (if (focus && !touchThrough) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    private fun passTouchesThrough(point: PointF?): Boolean {
        val view = island ?: return false
        val lp = window ?: return false
        if (point != null && !view.windowContains(point)) return false
        val enabled = point != null
        if (enabled == touchThrough) return enabled
        touchThrough = enabled
        lp.alpha = if (enabled && Build.VERSION.SDK_INT >= 31) {
            val maximum = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            (maximum - .03f).coerceIn(0f, 1f)
        } else 1f
        lp.flags = windowFlags(opening && !actionBusy)
        val updated = runCatching { manager.updateViewLayout(view, lp) }.isSuccess
        if (!updated) {
            touchThrough = false
            lp.alpha = 1f
            lp.flags = windowFlags(opening && !actionBusy)
        }
        return enabled && updated
    }

    @Suppress("DEPRECATION")
    private fun measureScreen(): IslandScreen {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val cutout = manager.defaultDisplay.cutout ?: metrics.windowInsets.displayCutout
            val waterfall = cutout?.waterfallInsets
            return IslandScreen(Rect(metrics.bounds), Rect(maxOf(bars.left, waterfall?.left ?: 0),
                maxOf(bars.top, waterfall?.top ?: 0), maxOf(bars.right, waterfall?.right ?: 0),
                maxOf(bars.bottom, waterfall?.bottom ?: 0)), cutouts(cutout, metrics.bounds))
        }
        val display = manager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        val bounds = Rect(0, 0, size.x, size.y)
        val cutout = if (Build.VERSION.SDK_INT >= 29) display.cutout else legacyCutout
        val status = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }?.let(resources::getDimensionPixelSize) ?: (24 * resources.displayMetrics.density).roundToInt()
        val bars = Rect(legacyBars).apply {
            top = maxOf(top, status)
            if (bottom == 0) bottom = (24 * resources.displayMetrics.density).roundToInt()
        }
        return IslandScreen(bounds, bars, cutouts(cutout, bounds))
    }

    private fun cutouts(value: DisplayCutout?, bounds: Rect): List<IslandCutout> {
        if (value == null || Build.VERSION.SDK_INT < 28) return emptyList()
        val contour = if (Build.VERSION.SDK_INT >= 31) value.cutoutPath else null
        if (Build.VERSION.SDK_INT >= 29) return listOf(
            Gravity.TOP to value.boundingRectTop, Gravity.LEFT to value.boundingRectLeft,
            Gravity.RIGHT to value.boundingRectRight, Gravity.BOTTOM to value.boundingRectBottom
        ).filterNot { (_, rect) -> rect.isEmpty }.map { (edge, rect) -> IslandCutout(edge, rect, contour) }
        return value.boundingRects.map { rect ->
            val edge = listOf(
                Gravity.TOP to rect.top - bounds.top, Gravity.LEFT to rect.left - bounds.left,
                Gravity.RIGHT to bounds.right - rect.right, Gravity.BOTTOM to bounds.bottom - rect.bottom
            ).minBy { it.second }.first
            IslandCutout(edge, rect)
        }
    }

    @Suppress("DEPRECATION")
    private fun watchLegacyInsets() {
        val probe = View(overlayContext)
        probe.setOnApplyWindowInsetsListener { _, insets ->
            val bars = Rect(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            if (bars != legacyBars || cutout != legacyCutout) {
                legacyBars = bars
                legacyCutout = cutout
                screen = null
                ui.post(::render)
            }
            insets
        }
        val params = layoutParams(false).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            alpha = 0f
        }
        runCatching { manager.addView(probe, params); legacyProbe = probe }
    }

    private fun removeIsland() {
        ui.removeCallbacks(settleLayout)
        island?.let { view ->
            draft = view.conversation.draft()
            view.conversation.hideKeyboard()
            runCatching { manager.removeViewImmediate(view) }
        }
        island = null
        window = null
        touchThrough = false
        transitionFrame.setEmpty()
        opening = false
        moving = false
        wakeExpanded = false
        legacyProbe?.let { runCatching { manager.removeViewImmediate(it) } }
        legacyProbe = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen = null
        ui.post(::render)
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        screen = null
        ui.post(::render)
    }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (CortexActionState.listener === actionStatus) CortexActionState.listener = null
        if (CortexRuntime.islandVisibility === visibilityChanged) CortexRuntime.islandVisibility = null
        if (CortexRuntime.wakeLevel === wakeLevel) CortexRuntime.wakeLevel = null
        if (CortexRuntime.islandTouchPassThrough === passThrough) CortexRuntime.islandTouchPassThrough = null
        if (running === this) running = null
        displays.unregisterDisplayListener(this)
        ui.removeCallbacksAndMessages(null)
        removeIsland()
        if (assistantDelegate.isInitialized()) assistant.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONFIG = "app.cortex.assistant.island.CONFIG"
        const val ACTION_ENSURE = "app.cortex.assistant.island.ENSURE"
        const val ACTION_WAKE = "app.cortex.assistant.island.WAKE"
        const val ACTION_WAKE_CLOSED = "app.cortex.assistant.island.WAKE_CLOSED"
        const val ACTION_SUBMIT = "app.cortex.assistant.island.SUBMIT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HIDE_LANDSCAPE = "hideLandscape"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_MESSAGE = "message"
        internal const val WAKE_PAUSED = "wakePausedByVoice"
        private val STOP_COMMAND = Regex("(?iu)^(?:стоп|остановись|отмена)[.!?]*$")
        private val HIDE_COMMAND = Regex("(?iu)^(?:убери|скрой|спрячь|закрой) (?:островок|динамический островок)$")
        private val POWER_COMMAND = Regex("(?iu)^(?:выключись|отключись|выключи себя|отключи себя|выключи кортекс|останови работу в фоне)$")
        private const val RESULT_MS = 6_800L
        private const val CHANNEL = "cortex-island"
        private const val NOTIFICATION = 2810
        private var running: CortexIslandService? = null

        internal fun handleControl(context: Context, raw: String): String? {
            val command = (CortexHotword.command(raw) ?: raw).trim().trimEnd('.', '!', '?').trim()
                .replace(Regex("\\s+"), " ")
            return when {
                HIDE_COMMAND.matches(command) -> {
                    running?.hideTemporarily()
                    "Островок скрыт до следующего вызова."
                }
                POWER_COMMAND.matches(command) -> {
                    context.getSharedPreferences("cortex-display", MODE_PRIVATE).edit().putBoolean(WAKE_PAUSED, true).apply()
                    CortexActionState.stop()
                    running?.let {
                        it.hideTemporarily()
                        if (it.assistantDelegate.isInitialized()) it.assistant.cancel()
                    }
                    context.stopService(Intent(context, CortexWakeService::class.java))
                    context.stopService(Intent(context, CortexIslandService::class.java))
                    CortexSupportNotification.pause(context)
                    CortexRuntime.reportWake(null)
                    CortexRuntime.shutdownListener?.invoke()
                    "Кортекс выключен в фоне."
                }
                else -> null
            }
        }

        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val preferences = context.getSharedPreferences("cortex-display", MODE_PRIVATE)
            if (preferences.getBoolean(WAKE_PAUSED, false) || preferences.getString("mode", "off") == "off") return
            val intent = Intent(context, CortexIslandService::class.java).apply { this.action = action; extras() }
            runCatching { context.startService(intent) }
        }
    }
}
