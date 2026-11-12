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
    init {
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
                        activity.getSharedPreferences("cortex-display", Activity.MODE_PRIVATE).edit().putString("mode",mode).putBoolean("hideLandscape",hide).apply()
                        if (mode == "off") activity.stopService(Intent(activity, CortexIslandService::class.java))
                        else if (Settings.canDrawOverlays(activity)) {
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
                    "wake-config" -> {
                        val enabled = objectValue.optBoolean("enabled", false)
                        if (!enabled) {
                            activity.stopService(Intent(activity, CortexWakeService::class.java))
                            status("wake-config", true, "Выключено")
                        } else if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            status("wake-config", false, "Сначала разрешите микрофон")
                        } else if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                            status("wake-config", false, "На устройстве нет системного офлайн-распознавания")
                        } else {
                            runCatching { activity.startForegroundService(Intent(activity, CortexWakeService::class.java)) }
                                .onSuccess { status("wake-config", true, "Запуск голосовой службы запрошен. Проверьте индикатор микрофона Android.") }
                                .onFailure { status("wake-config", false, "Android ограничил фоновый микрофон") }
                        }
                    }
                    "island-state" -> CortexIslandService.send(activity, CortexIslandService.ACTION_STATUS) {
                        putExtra(CortexIslandService.EXTRA_PHASE, objectValue.optString("phase"))
                        putExtra(CortexIslandService.EXTRA_MESSAGE, objectValue.optString("message").take(64))
                    }
                }
            }
        }
    }
    private fun status(type: String, ok: Boolean, message: String) {
        runCatching { reply?.postMessage(JSONObject().put("type", type).put("ok", ok).put("message", message).toString()) }
    }
    /** Re-applies the saved island mode (e.g. after the user granted "display over other apps"). */
    fun ensureIsland() {
        val mode = activity.getSharedPreferences("cortex-display", Activity.MODE_PRIVATE).getString("mode", "off") ?: "off"
        if (mode == "off" || !Settings.canDrawOverlays(activity)) return
        runCatching {
            activity.startForegroundService(Intent(activity, CortexIslandService::class.java).apply {
                action = CortexIslandService.ACTION_ENSURE
            })
        }
    }
    fun refresh() {
        val audio = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        runCatching { reply?.postMessage(JSONObject()
            .put("type", "permissions")
            .put("microphone", audio)
            .put("notifications", notifications)
            .put("overlay", Settings.canDrawOverlays(activity)).toString()) }
    }
}
