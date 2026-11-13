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
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private var actionBusy = false
    private var invokedFrom: String? = null
    private val actionStatus: (String, String) -> Unit = { state, text ->
        actionBusy = state == "executing"
        if (!busy) updateStatus(state, text)
    }
    private val visibilityChanged: () -> Unit = { render() }

    private val settleLayout = Runnable { island?.let { placeIsland(it) } }
    private val returnToIdle = Runnable { updateStatus("idle", "") }
    private var repaintPending = false
    private val repaintAnswer = Runnable {
        repaintPending = false
        if (busy) render()
    }

    override fun onCreate() {
        super.onCreate()
        CortexActionState.listener = actionStatus
        CortexRuntime.islandVisibility = visibilityChanged
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIG) {
            val requested = intent.getStringExtra(EXTRA_MODE)
            if (requested in setOf("always", "wake", "off")) {
                preferences.edit().putString("mode", requested)
                    .putBoolean(EXTRA_HIDE_LANDSCAPE, intent.getBooleanExtra(EXTRA_HIDE_LANDSCAPE, true)).apply()
            }
        }
        mode = preferences.getString("mode", "off") ?: "off"
        if (mode == "off") {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STATUS -> if (!busy && !opening) updateStatus(
                intent.getStringExtra(EXTRA_PHASE) ?: "idle", intent.getStringExtra(EXTRA_MESSAGE).orEmpty().take(64)
            )
            ACTION_WAKE -> if (!busy && !actionBusy) {
                updateStatus("listening", "Слушаю команду…")
            }
            ACTION_WAKE_CLOSED -> if (phase == "listening" && !busy) updateStatus("idle", "")
            ACTION_SUBMIT -> {
                val text = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                if (text.isNotBlank() && canShow()) {
                    if (text.trim().matches(STOP_COMMAND)) stopAnswer()
                    else if (!busy && !actionBusy) submit(text, fromVoice = true)
                }
            }
            ACTION_DISMISS -> if (!busy) updateStatus("idle", "")
            else -> render()
        }
        return START_STICKY
    }

    private fun canShow() = Settings.canDrawOverlays(this) &&
        (!preferences.getBoolean(EXTRA_HIDE_LANDSCAPE, true) ||
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)

    private fun updateStatus(state: String, text: String) {
        ui.removeCallbacks(returnToIdle)
        phase = state
        message = text
        if (!busy) {
            val delay = when (state) {
                "done", "error" -> if (reply.isNotBlank()) 12_000L else 2600L
                "listening" -> 9_000L
                "understood" -> 12_000L
                "executing" -> 45_000L
                else -> 0L
            }
            if (delay > 0) ui.postDelayed(returnToIdle, delay)
        }
        render()
    }

    private fun render() {
        if (CortexRuntime.appVisible || !canShow() || (mode == "wake" && phase == "idle" && !opening && !moving)) {
            removeIsland()
            return
        }
        if (Build.VERSION.SDK_INT in 26..28 && legacyProbe == null) watchLegacyInsets()
        val geometryChanged = screen == null
        val nextScreen = screen ?: measureScreen().also { screen = it }
        val visible = island ?: createIsland(nextScreen) ?: return
        val label = when (phase) {
            "listening" -> message.ifBlank { "Слушаю…" }
            "executing" -> message.replaceFirst(STEP_LABEL, "$1/$2 · ").ifBlank { "Думаю…" }
            "done" -> if (reply.isNotBlank()) "Ответ готов" else "Готово"
            "error" -> if (reply.isNotBlank()) "Открыть ответ" else message.ifBlank { "Не удалось выполнить" }
            "understood" -> message
            else -> ""
        }
        val wasStatus = visible.tag == true
        visible.tag = label.isNotEmpty()
        visible.phase(phase, label)
        visible.haptics = preferences.getBoolean("haptics", true)
        visible.reduceMotion(preferences.getBoolean("reduceMotion", false))
        visible.conversation.status(phase, label)
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
            { setExpanded(false) }, ::openApplication)
        screen = currentScreen
        val params = layoutParams()
        window = params
        placeIsland(view, update = false)
        return runCatching { manager.addView(view, params); view }.onSuccess { island = it }
            .onFailure { window = null }.getOrNull()
    }

    private fun setExpanded(open: Boolean, focus: Boolean = false) {
        val view = island ?: return
        if (open == opening) {
            if (focus) view.conversation.focusEditor()
            return
        }
        ui.removeCallbacks(settleLayout)
        if (open) CortexScreenService.connected?.activePackage()?.takeIf { CortexScreenService.supportsPackage(it) }?.let { invokedFrom = it }
        opening = open
        moving = true
        if (!open) view.conversation.hideKeyboard()
        placeIsland(view)
        view.expand(open, !preferences.getBoolean("reduceMotion", false)) {
            moving = false
            placeIsland(view)
            if (open && focus) view.conversation.focusEditor()
            if (!open && phase == "idle") render()
        }
    }

    private fun placeIsland(view: LiquidIslandView, update: Boolean = true, deform: Boolean = false) {
        val viewport = screen ?: return
        val lp = window ?: return
        val d = view.resources.displayMetrics.density
        val safe = viewport.safeArea(d)
        val panelWidth = minOf(safe.width(), 326 * d).roundToInt().coerceAtLeast(1)
        view.conversation.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(safe.height().roundToInt().coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        val placement = IslandPlacement(viewport, d, view.tag == true, panelWidth, view.conversation.measuredHeight)
        val box = Rect(placement.frame(opening, moving))
        val previous = Rect(lp.x + viewport.bounds.left, lp.y + viewport.bounds.top,
            lp.x + lp.width + viewport.bounds.left, lp.y + lp.height + viewport.bounds.top)
        val transition = deform && !previous.isEmpty && box != previous &&
            !preferences.getBoolean("reduceMotion", false) && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (transition) {
            box.union(previous)
            ui.removeCallbacks(settleLayout)
            ui.postDelayed(settleLayout, LiquidIslandView.GEOMETRY_MS)
        }
        view.place(placement, box, transition)
        val flags = windowFlags(if (moving) opening || view.hasWindowFocus() else opening)
        if (box == previous && flags == lp.flags) return
        lp.width = box.width().coerceAtLeast(1)
        lp.height = box.height().coerceAtLeast(1)
        lp.x = box.left - viewport.bounds.left
        lp.y = box.top - viewport.bounds.top
        lp.flags = flags
        if (update && view.isAttachedToWindow) runCatching { manager.updateViewLayout(view, lp) }
    }

    private fun submit(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim().take(2400)
        if (busy || actionBusy || text.isEmpty()) return
        if (fromVoice) invokedFrom = CortexScreenService.connected?.activePackage()
        if (fromVoice) island?.conversation?.hideKeyboard()
        busy = true
        reply = ""
        updateStatus("executing", "Думаю…")
        setExpanded(true)
        val streamed = StringBuilder()
        assistant.send(text, preferences.getString("profile", "auto") ?: "auto", invokedFrom,
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
        updateStatus("done", "")
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
            (if (focus) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

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
        opening = false
        moving = false
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
        displays.unregisterDisplayListener(this)
        ui.removeCallbacksAndMessages(null)
        removeIsland()
        if (assistantDelegate.isInitialized()) assistant.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONFIG = "app.cortex.assistant.island.CONFIG"
        const val ACTION_STATUS = "app.cortex.assistant.island.STATUS"
        const val ACTION_ENSURE = "app.cortex.assistant.island.ENSURE"
        const val ACTION_DISMISS = "app.cortex.assistant.island.DISMISS"
        const val ACTION_WAKE = "app.cortex.assistant.island.WAKE"
        const val ACTION_WAKE_CLOSED = "app.cortex.assistant.island.WAKE_CLOSED"
        const val ACTION_SUBMIT = "app.cortex.assistant.island.SUBMIT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HIDE_LANDSCAPE = "hideLandscape"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_MESSAGE = "message"
        private val STOP_COMMAND = Regex("(?iu)^(?:стоп|остановись|отмена)[.!?]*$")
        private val STEP_LABEL = Regex("^Шаг (\\d+) из (\\d+) · ")
        private const val CHANNEL = "cortex-island"
        private const val NOTIFICATION = 2810

        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, CortexIslandService::class.java).apply { this.action = action; extras() }
            runCatching { context.startService(intent) }
        }
    }
}
