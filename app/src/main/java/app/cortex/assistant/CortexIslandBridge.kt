package app.cortex.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Independent, origin-scoped Android message channel. Install from MainActivity.onCreate. */
class CortexIslandBridge(private val activity: Activity, private val web: WebView, origin: String) {
    private var reply: JavaScriptReplyProxy? = null
    private val prefs = activity.getSharedPreferences("cortex-display", Activity.MODE_PRIVATE)
    private val wakeListener: (String?) -> Unit = { status("wake-state", true, it.orEmpty()) }
    init {
        CortexRuntime.wakeStatusListener = wakeListener
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "CortexIslandControl", setOf(origin)) {
                _, message, sourceOrigin, mainFrame, response ->
                if (!mainFrame || sourceOrigin.toString().trimEnd('/') != origin || (message.data?.length ?: 0) > 3000) return@addWebMessageListener
                val objectValue = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                reply = response
                when (objectValue.optString("operation")) {
                    "permissions" -> refresh()
                    "overlay-permission" -> runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                    }.onFailure { status("permission", false, "Не удалось открыть настройки поверх приложений") }
                    "microphone-permission" -> {
                        if (Build.VERSION.SDK_INT >= 23) activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6291)
                        else refresh()
                    }
                    "notification-permission" -> {
                        if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 6292)
                        else refresh()
                    }
                    "overlay-config" -> {
                        val mode = objectValue.optString("mode", "off")
                        val hide = objectValue.optBoolean("hideLandscape", true)
                        if (mode !in setOf("always", "wake", "off")) return@addWebMessageListener
                        prefs.edit().putString("mode", mode).putBoolean("hideLandscape", hide).apply()
                        if (mode == "off") {
                            configureWake(false)
                            activity.stopService(Intent(activity, CortexIslandService::class.java))
                        } else if (Settings.canDrawOverlays(activity)) {
                            val intent = Intent(activity, CortexIslandService::class.java).apply {
                                action = CortexIslandService.ACTION_CONFIG
                                putExtra(CortexIslandService.EXTRA_MODE, mode)
                                putExtra(CortexIslandService.EXTRA_HIDE_LANDSCAPE, hide)
                            }
                            runCatching { activity.startForegroundService(intent) }.onFailure {
                                status("overlay-mode", false, "Android ограничил запуск островка")
                            }
                        } else status("overlay-mode", false, "Разрешите показ поверх приложений")
                    }
                    "island-options" -> {
                        val profile = objectValue.optString("profile").takeIf { it in setOf("auto", "fast", "deep") } ?: "auto"
                        prefs.edit().putString("profile", profile)
                            .putBoolean("phoneActions", objectValue.optBoolean("phoneActions", true))
                            .putBoolean("reduceMotion", objectValue.optBoolean("reduceMotion"))
                            .putBoolean("haptics", objectValue.optBoolean("haptics", true)).apply()
                        if (prefs.getString("mode", "off") != "off" && Settings.canDrawOverlays(activity)) {
                            CortexIslandService.send(activity, CortexIslandService.ACTION_ENSURE)
                        }
                    }
                    "wake-config" -> configureWake(objectValue.optBoolean("enabled"))
                    "island-state" -> CortexIslandService.send(activity, CortexIslandService.ACTION_STATUS) {
                        putExtra(CortexIslandService.EXTRA_PHASE, objectValue.optString("phase"))
                        putExtra(CortexIslandService.EXTRA_MESSAGE, objectValue.optString("message").take(64))
                    }
                }
            }
        }
    }
    private fun configureWake(enabled: Boolean) {
        val intent = Intent(activity, CortexWakeService::class.java)
        if (!enabled) {
            activity.stopService(intent)
            CortexRuntime.reportWake(null)
            return
        }
        val problem = when {
            !Settings.canDrawOverlays(activity) -> "Разрешите показ островка поверх приложений."
            prefs.getString("mode", "off") == "off" -> "Включите режим островка."
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Разрешите доступ к микрофону."
            Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) ->
                "На устройстве нет офлайн-распознавания Android."
            else -> null
        }
        if (problem != null) {
            status("wake-config", false, problem)
            return
        }
        ensureIsland()
        if (CortexRuntime.wakeHook == null) {
            runCatching { activity.startForegroundService(intent) }
                .onFailure { status("wake-config", false, "Android ограничил запуск микрофона.") }
        }
    }

    fun close() {
        if (CortexRuntime.wakeStatusListener === wakeListener) CortexRuntime.wakeStatusListener = null
        reply = null
    }

    private fun status(type: String, ok: Boolean, message: String) {
        runCatching { reply?.postMessage(JSONObject().put("type", type).put("ok", ok).put("message", message).toString()) }
    }
    /** Re-applies the saved island mode (e.g. after the user granted "display over other apps"). */
    fun ensureIsland() {
        val mode = prefs.getString("mode", "off") ?: "off"
        if (mode == "off" || !Settings.canDrawOverlays(activity)) return
        runCatching {
            activity.startForegroundService(Intent(activity, CortexIslandService::class.java).apply {
                action = CortexIslandService.ACTION_ENSURE
            })
        }
    }
    @Suppress("DEPRECATION")
    private fun previewCamera(): String {
        if (Build.VERSION.SDK_INT < 28) return "none"
        val insets = activity.window.decorView.rootWindowInsets?.displayCutout
        val cutout = if (Build.VERSION.SDK_INT >= 29) activity.windowManager.defaultDisplay.cutout ?: insets else insets
        if (cutout == null) return "none"
        val edge = 2 * activity.resources.displayMetrics.density
        val top = if (Build.VERSION.SDK_INT >= 29) cutout.boundingRectTop.takeUnless { it.isEmpty }
        else cutout.boundingRects.firstOrNull { it.top <= edge && it.left > edge }
        if (top != null) return if (top.top <= edge) "drop" else "punch"
        if (Build.VERSION.SDK_INT >= 29) {
            if (!cutout.boundingRectLeft.isEmpty) return "left"
            if (!cutout.boundingRectRight.isEmpty) return "right"
        }
        return "none"
    }

    fun refresh() {
        val audio = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        runCatching { reply?.postMessage(JSONObject()
            .put("type", "permissions")
            .put("microphone", audio)
            .put("notifications", notifications)
            .put("islandCamera", previewCamera())
            .put("overlay", Settings.canDrawOverlays(activity)).toString()) }
        CortexRuntime.wakeStatus?.let(wakeListener)
    }
}
