package app.cortex.assistant

import android.app.DownloadManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class CortexPhoneActions(private val context: Context) {
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val permissionStore by lazy { context.getSharedPreferences("cortex-device", Context.MODE_PRIVATE) }
    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun execute(command: JSONObject): JSONObject {
        val kind = command.optString("kind")
        val target = command.optString("target")
        val mode = command.optString("mode")
        fun status(value: String) = JSONObject().put("status", value)
        fun started(intent: Intent) = status(if (launch(intent)) "opened" else "unavailable")
        when (kind) {
            "app" -> {
                val known = mapOf("telegram" to "org.telegram.messenger", "tiktok" to "com.zhiliaoapp.musically",
                    "instagram" to "com.instagram.android", "youtube" to "com.google.android.youtube",
                    "whatsapp" to "com.whatsapp", "chrome" to "com.android.chrome")
                val pkg = known[target.lowercase(Locale.ROOT)] ?: target
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return status("not_installed")
                return started(intent).put("name", target).put("verified", false)
            }
            "app_list" -> {
                @Suppress("DEPRECATION")
                val names = context.packageManager.getInstalledApplications(0).mapNotNull {
                    runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull()
                }.filter { it.contains(command.optString("query"), true) }.distinct().sorted().take(70)
                return status("done").put("count", names.size).put("names", JSONArray(names))
            }
            "app_info" -> return started(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$target"))).put("name", target)
            "settings" -> {
                val action = when (target) {
                    "wifi", "wifi_ip" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "mobile", "network_operator", "apn", "data_usage" -> Settings.ACTION_DATA_ROAMING_SETTINGS
                    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "nfc", "nfc_payment" -> Settings.ACTION_NFC_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "apps", "default_apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "display", "rotate", "night_light" -> Settings.ACTION_DISPLAY_SETTINGS
                    "language", "region", "regional" -> Settings.ACTION_LOCALE_SETTINGS
                    "date" -> Settings.ACTION_DATE_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "security", "privacy" -> Settings.ACTION_SECURITY_SETTINGS
                    "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                    "battery", "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "dnd" -> Settings.ACTION_SOUND_SETTINGS
                    "vpn" -> Settings.ACTION_VPN_SETTINGS
                    "keyboard" -> Settings.ACTION_INPUT_METHOD_SETTINGS
                    "accounts" -> Settings.ACTION_ADD_ACCOUNT
                    "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val intent = Intent(action)
                if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                return started(intent).put("fallback", action == Settings.ACTION_SETTINGS)
            }
            "connectivity", "panel" -> {
                val action = when (target) {
                    "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                    "internet", "mobile" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "nfc" -> Settings.ACTION_NFC_SETTINGS
                    else -> Settings.ACTION_WIRELESS_SETTINGS
                }
                return status(if (launch(Intent(action))) "panel" else "unavailable")
            }
            "utility" -> return when (target) {
                "camera_photo" -> started(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                "camera_video" -> started(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
                "downloads" -> started(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                "keyboard" -> { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); status("opened") }
                else -> status("invalid")
            }
            "battery" -> return readDevice("battery")
            "read_device" -> return readDevice(target)
            "volume" -> {
                val stream = when (command.optString("stream")) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call" -> AudioManager.STREAM_VOICE_CALL
                    else -> AudioManager.STREAM_MUSIC
                }
                val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                val current = audio.getStreamVolume(stream)
                val change = command.optInt("level", 10)
                val currentPercent = current * 100 / maximum
                val percent = when (mode) { "set" -> change; "up" -> currentPercent + change; "down" -> currentPercent - change
                    "mute" -> 0; "unmute" -> 50; else -> currentPercent }.coerceIn(0, 100)
                if (mode != "status") audio.setStreamVolume(stream, percent * maximum / 100, 0)
                return status("done").put("level", audio.getStreamVolume(stream) * 100 / maximum)
            }
            "brightness" -> {
                if (mode == "status") return status("done").put("level", (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255))
                if (!Settings.System.canWrite(context)) return status("needs_brightness")
                val resolver = context.contentResolver
                val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
                val level = when (mode) { "set" -> command.optInt("level", current); "up" -> current + command.optInt("level", 10);
                    "down" -> current - command.optInt("level", 10); else -> current }.coerceIn(1, 100)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (mode == "auto") Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                if (mode !in listOf("auto", "manual")) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level * 255 / 100)
                return status("done").put("level", level)
            }
            "torch" -> {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val camera = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return status("unsupported")
                val previous = permissionStore.getBoolean("torch", false)
                val enabled = when (mode) { "on" -> true; "off" -> false; "toggle" -> !previous; else -> previous }
                if (mode != "status") { manager.setTorchMode(camera, enabled); permissionStore.edit().putBoolean("torch", enabled).apply() }
                return status("done").put("enabled", enabled)
            }
            "ringer" -> {
                audio.ringerMode = when (mode) { "vibrate" -> AudioManager.RINGER_MODE_VIBRATE;
                    "silent" -> AudioManager.RINGER_MODE_SILENT; else -> AudioManager.RINGER_MODE_NORMAL }
                return status("done")
            }
            "lock_screen" -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isAdminActive(ComponentName(context, CortexDeviceAdmin::class.java))) return status("needs_lock")
                dpm.lockNow()
                return status("done")
            }
            "home" -> return started(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            "timer" -> return started(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, command.optInt("seconds")))
            "alarm" -> return started(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, command.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, command.optInt("minute")))
            "dial" -> return started(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(command.optString("number")))))
            "sms" -> return started(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(command.optString("number"))))
                .putExtra("sms_body", command.optString("text")))
            "search" -> return started(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(command.optString("query")))))
            "maps" -> return started(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(command.optString("query")))))
            "calendar" -> return started(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, command.optString("title")))
            "share_text" -> return started(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, command.optString("text"))
            }, "Поделиться текстом"))
            "copy_text" -> {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("CORTEX", command.optString("text")))
                return status("done")
            }
        }
        return status("unsupported")
    }

    private fun readDevice(target: String): JSONObject {
        fun done() = JSONObject().put("status", "done")
        return when (target) {
            "battery" -> {
                val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                done().put("level", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", battery.isCharging)
            }
            "storage" -> { val f = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                done().put("free", f.availableBytes).put("total", f.totalBytes) }
            "phone" -> done().put("value", Build.MANUFACTURER + " " + Build.MODEL)
            "ringer" -> done().put("value", when(audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "sound" })
            "time" -> done().put("value", java.time.ZonedDateTime.now().toString())
            "locale" -> done().put("value", Locale.getDefault().toLanguageTag())
            "wifi" -> done().put("enabled", (context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled)
            "network" -> { val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val active = cm.activeNetwork; val cap = cm.getNetworkCapabilities(active)
                val value = when { cap == null -> "offline"; cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; else -> "connected" }
                done().put("value", value).put("validated", cap?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) }
            "airplane" -> done().put("enabled", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
            "location" -> done().put("enabled", (context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled)
            "battery_saver" -> done().put("enabled", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
            "screen_lock" -> done().put("enabled", (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure)
            else -> JSONObject().put("status", "unsupported")
        }
    }
}
