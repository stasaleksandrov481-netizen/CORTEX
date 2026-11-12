package app.cortex.assistant

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
    private val phone by lazy { CortexPhoneActions(activity) }
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
                val result = if (command == null) JSONObject().put("status", "invalid") else runCatching { phone.execute(command) }
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

}
