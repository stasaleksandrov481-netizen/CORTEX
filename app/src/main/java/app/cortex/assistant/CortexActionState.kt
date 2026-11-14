package app.cortex.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One phone task at a time, independent of which CORTEX surface started it. */
internal object CortexActionState {
    data class Status(val phase: String, val message: String, val at: Long = android.os.SystemClock.uptimeMillis())

    private var cancel: (() -> Unit)? = null
    var status: Status? = null
        private set
    var listener: ((Status) -> Unit)? = null
    var gate = "open"
        private set

    fun setGate(mode: String) {
        gate = if (mode in setOf("maintenance", "update")) mode else "open"
        if (gate != "open") stop()
    }

    fun acquire(onStop: () -> Unit): Boolean {
        if (cancel != null || gate != "open") return false
        cancel = onStop
        return true
    }

    fun stop() { cancel?.invoke() }

    fun report(context: Context, phase: String, message: String) {
        val active = phase == "executing"
        if (!active) cancel = null
        val next = Status(phase, message)
        status = next
        listener?.invoke(next)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!active) {
            manager.cancel(NOTIFICATION)
        } else if (manager.areNotificationsEnabled()) {
            manager.createNotificationChannel(NotificationChannel("cortex-actions", "Задачи CORTEX", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getBroadcast(context, 15, Intent(context, CortexStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(context, 16, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, "cortex-actions").setSmallIcon(R.drawable.ic_cortex)
                .setContentTitle("CORTEX выполняет задачу").setContentText(message).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
            runCatching { manager.notify(NOTIFICATION, notification) }
        }
    }

    fun log(context: Context, message: String) {
        val store = context.getSharedPreferences("cortex-action-log", Context.MODE_PRIVATE)
        val old = history(context)
        val list = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("message", message.take(160)))
        for (i in 0 until minOf(old.length(), 39)) list.put(old.get(i))
        store.edit().putString("entries", list.toString()).apply()
    }

    fun history(context: Context): JSONArray = runCatching {
        val store = context.getSharedPreferences("cortex-action-log", Context.MODE_PRIVATE)
        JSONArray(store.getString("entries", "[]"))
    }.getOrDefault(JSONArray())

    private const val NOTIFICATION = 1049
}

class CortexStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { CortexActionState.stop() }
}

internal object CortexConfirmation {
    data class Prompt(val id: String, val title: String, val detail: String, val accept: String, val done: (Boolean) -> Unit)
    var prompt: Prompt? = null
        private set
    var dismiss: (() -> Unit)? = null

    fun show(context: Context, title: String, detail: String, accept: String, done: (Boolean) -> Unit) {
        check(prompt == null)
        val id = UUID.randomUUID().toString()
        prompt = Prompt(id, title, detail, accept, done)
        try {
            context.startActivity(Intent(context, CortexConfirmationActivity::class.java)
                .putExtra("prompt", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { respond(id, false) }
    }

    fun respond(id: String, accepted: Boolean) {
        val current = prompt?.takeIf { it.id == id } ?: return
        prompt = null
        dismiss?.invoke()
        dismiss = null
        current.done(accepted)
    }

    fun cancel() {
        val current = prompt ?: return
        respond(current.id, false)
    }
}

class CortexConfirmationActivity : Activity() {
    private var id = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("prompt").orEmpty()
        val prompt = CortexConfirmation.prompt?.takeIf { it.id == id } ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dialog = AlertDialog.Builder(this)
            .setTitle(prompt.title).setMessage(prompt.detail)
            .setPositiveButton(prompt.accept) { _, _ -> CortexConfirmation.respond(id, true) }
            .setNegativeButton("Остановить") { _, _ -> CortexConfirmation.respond(id, false) }
            .setOnCancelListener { CortexConfirmation.respond(id, false) }
            .create()
        CortexConfirmation.dismiss = { dialog.dismiss(); finish() }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) dialog.window?.setHideOverlayWindows(true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) CortexConfirmation.respond(id, false)
    }
}
