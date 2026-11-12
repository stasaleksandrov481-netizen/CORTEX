package app.cortex.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Kotlin implementation of the web page's `CortexDevice` message contract.
 * The original Kotlin implementation cannot be recovered bit-for-bit from an APK.
 * The app never claims that a system action succeeded without a platform result.
 */
internal class CortexDeviceBridge(
    private val activity: Activity,
    private val speech: CortexSpeech,
    private val emit: (JSONObject) -> Unit
) {
    private val context get() = activity.applicationContext
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val permissionStore by lazy { context.getSharedPreferences("cortex-device", Context.MODE_PRIVATE) }
    private var pendingImage = false
    private var pendingExport: String? = null

    fun handle(raw: String) {
        val body = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (body.optString("operation")) {
            "permissions" -> refreshPermissions()
            "open-settings" -> sendAction("settings") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            "assistant-permission" -> open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            "verification-permission" -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "lock-permission" -> {
                val admin = ComponentName(context, CortexDeviceAdmin::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                open(intent)
            }
            "brightness-permission" -> open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            "notification-permission" -> requestPermission(Manifest.permission.POST_NOTIFICATIONS)
            "microphone-permission" -> requestPermission(Manifest.permission.RECORD_AUDIO)
            "tap" -> vibrate()
            "phone-action" -> {
                val id = body.optString("id")
                val command = body.optJSONObject("command")
                val result = if (command == null) JSONObject().put("status", "invalid") else runCatching { phone(command) }
                    .getOrElse { JSONObject().put("status", "unavailable") }
                emit(JSONObject().put("type", "phone").put("id", id).put("result", result))
            }
            "voice-start" -> {
                val id = body.optString("id")
                if (android.os.Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    emit(JSONObject().put("type", "voice").put("id", id).put("phase", "error")
                        .put("text", "Разрешите микрофон CORTEX в Android."))
                    requestPermission(Manifest.permission.RECORD_AUDIO)
                } else speech.listen(id, body.optString("language", "ru-RU"))
            }
            "voice-finish" -> speech.finish(body.optString("id"))
            "voice-cancel" -> speech.cancel(body.optString("id"))
            "speak" -> speech.speak(body.optString("id"), body.optString("text"),
                body.optString("language", "ru-RU"), body.optDouble("speed", 1.0).toFloat())
            "silence" -> speech.silence(body.optString("id").takeIf { it.isNotBlank() })
            "copy" -> {
                val text = body.optString("text").take(200_000)
                val ok = runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("CORTEX", text))
                }.isSuccess
                emit(JSONObject().put("type", "copy").put("ok", ok))
            }
            "open-link" -> {
                val url = body.optString("url")
                val ok = if (url.startsWith("https://") || url.startsWith("http://")) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else false
                emit(JSONObject().put("type", "link").put("ok", ok))
            }
            "open-login" -> {
                val url = body.optString("url")
                val ok = url.startsWith("https://") && launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                emit(JSONObject().put("type", "browser").put("ok", ok))
            }
            "choose-image" -> {
                pendingImage = true
                @Suppress("DEPRECATION")
                activity.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 501)
            }
            "export" -> {
                pendingExport = body.optString("text").take(2_000_000)
                @Suppress("DEPRECATION")
                activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "cortex-chats.txt")
                }, 502)
            }
            "screen-context" -> {
                // No silent screen capture. Accessibility screen reading is not part of this source rebuild.
                emit(JSONObject().put("type", "screen-context").put("context", JSONObject.NULL))
            }
            "overlay-config", "overlay-permission" -> emit(JSONObject().put("type", "overlay-mode").put("ok", false)
                .put("message", "В версии 0.8.6 нет системной службы островка."))
            "wake-config" -> emit(JSONObject().put("type", "wake-config").put("ok", false)
                .put("message", "Фоновая активация голосом не включена в этом исходном проекте."))
        }
    }

    private fun requestPermission(name: String) {
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) {
            if (name == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return
            activity.requestPermissions(arrayOf(name), 801)
        } else refreshPermissions()
    }
    fun refreshPermissions() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val obj = JSONObject().put("type", "permissions")
            .put("microphone", Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("notifications", Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .put("screenLock", dpm.isAdminActive(ComponentName(context, CortexDeviceAdmin::class.java)))
            .put("brightness", Settings.System.canWrite(context))
            .put("verifyApps", false)
            .put("overlay", Settings.canDrawOverlays(context))
        emit(obj)
    }
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 501 && pendingImage) {
            pendingImage = false
            val e = JSONObject().put("type", "image")
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val value = runCatching {
                    context.contentResolver.openInputStream(data.data!!)?.use { stream ->
                        val source = BitmapFactory.decodeStream(stream) ?: return@use null
                        val scaled = if (source.width > 1152 || source.height > 1152) {
                            val ratio = 1152.0 / maxOf(source.width, source.height)
                            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true)
                        } else source
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
                        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                }.getOrNull()
                e.put("ok", !value.isNullOrBlank())
                if (!value.isNullOrBlank()) e.put("data", value)
            } else e.put("cancelled", true)
            emit(e)
        }
        if (requestCode == 502) {
            val e = JSONObject().put("type", "export")
            val text = pendingExport
            pendingExport = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && text != null) {
                e.put("ok", runCatching {
                    context.contentResolver.openOutputStream(data.data!!)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess)
            } else e.put("cancelled", true)
            emit(e)
        }
    }
    private fun open(intent: Intent): Boolean = launch(intent)
    private fun sendAction(type: String, action: () -> Boolean) { emit(JSONObject().put("type", type).put("ok", action())) }
    private fun launch(intent: Intent): Boolean = runCatching { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); activity.startActivity(intent); true }.getOrDefault(false)
    private fun vibrate() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26 && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun phone(command: JSONObject): JSONObject {
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
                    "dnd" -> Settings.ACTION_ZEN_MODE_SETTINGS
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
                return started(Intent(Settings.ACTION_WIRELESS_SETTINGS)).put("status", "panel")
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
                return status("done").put("level", percent)
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
                val camera = manager.cameraIdList.firstOrNull() ?: return status("unsupported")
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
