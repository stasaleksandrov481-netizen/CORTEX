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
) : AutoCloseable {
    private val context get() = activity.applicationContext
    private val phone by lazy { CortexPhoneActions(activity) }
    private val engineDelegate = lazy { CortexActionEngine(activity) }
    private val engine by engineDelegate
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
                fun reply(result: JSONObject) = emit(JSONObject().put("type", "phone").put("id", id).put("result", result))
                if (command == null) reply(JSONObject().put("status", "invalid"))
                else if (CortexActionState.gate != "open") reply(JSONObject().put("status", "disabled"))
                else phone.executeChecked(command) { result ->
                    CortexActionState.log(context, "Команда Android: " + command.optString("kind") + " · " + result.optString("status"))
                    reply(result)
                }
            }
            "action-start", "action-plan" -> {
                val id = body.optString("id")
                fun event(phase: String, text: String = "", ok: Boolean = false) =
                    emit(JSONObject().put("type", "action").put("id", id).put("phase", phase).put("text", text).put("ok", ok))
                val request = body.optString("text")
                val profile = body.optString("profile")
                if (body.optString("operation") == "action-plan") {
                    val commands = body.optJSONArray("commands")
                    if (commands == null || commands.length() !in 1..CortexActionSequence.MAX_STEPS) {
                        event("result", "Уточните шаги задачи.")
                        return
                    }
                    val steps = (0 until commands.length()).mapNotNull(commands::optJSONObject)
                    if (steps.size != commands.length()) {
                        event("result", "План действий не удалось прочитать.")
                        return
                    }
                    engine.runPlan(steps, request, profile, progress = { event("progress", it) }) { result ->
                        emit(JSONObject().put("type", "action").put("id", id).put("phase", "result")
                            .put("text", result.text).put("ok", result.ok).put("external", result.external)
                            .put("observations", result.observations).put("blocked", result.blocked))
                    }
                } else {
                    val handled = engine.start(request, profile,
                        progress = { event("progress", it) }, finished = { result, ok -> event("result", result, ok) })
                    if (!handled) event("pass")
                }
            }
            "action-stop" -> if (engineDelegate.isInitialized()) engine.cancel()
            "action-history" -> emit(JSONObject().put("type", "action-history").put("entries", CortexActionState.history(context)))
            "gate-state" -> CortexActionState.setGate(body.optString("mode"))
            "support-notify-config" -> CortexSupportNotification.configure(context, body.optBoolean("enabled"))
            "support-notification" -> body.optJSONArray("ids")?.let {
                CortexSupportNotification.update(context, it, body.optBoolean("seen"))
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

    override fun close() {
        if (engineDelegate.isInitialized()) engine.close()
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
            .put("screenLock", dpm.isAdminActive(ComponentName(context, CortexDeviceAdmin::class.java)) ||
                (Build.VERSION.SDK_INT >= 28 && CortexScreenService.connected != null))
            .put("screenLockSupported", true)
            .put("screenAutomation", CortexScreenService.connected != null)
            .put("capabilities", phone.capabilities())
            .put("brightness", Settings.System.canWrite(context))
            .put("verifyApps", CortexScreenService.connected != null)
            .put("overlay", Settings.canDrawOverlays(context))
        emit(obj)
    }
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 501 && pendingImage) {
            pendingImage = false
            val e = JSONObject().put("type", "image")
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val value = runCatching {
                    val uri = requireNotNull(data.data)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1500) sample *= 2
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null
                    try {
                        val output = ByteArrayOutputStream()
                        for (quality in listOf(82, 68, 54, 40, 28)) {
                            output.reset()
                            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= 174_000) {
                                return@runCatching "data:image/jpeg;base64," +
                                    android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
                            }
                        }
                        null
                    } finally { bitmap.recycle() }
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
