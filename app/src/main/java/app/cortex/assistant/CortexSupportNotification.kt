package app.cortex.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONArray

internal object CortexSupportNotification {
    private const val JOB = 1053
    private const val CHANNEL = "cortex-support"
    private val id = Regex("^[0-9a-fA-F-]{36}$")
    private fun preferences(context: Context) = context.getSharedPreferences("cortex-support", Context.MODE_PRIVATE)

    fun configure(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            jobs.cancel(JOB)
            return
        }
        if (jobs.getPendingJob(JOB) == null) jobs.schedule(JobInfo.Builder(JOB, ComponentName(context, CortexSupportJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).build())
    }

    fun update(context: Context, ids: JSONArray, seen: Boolean = false) {
        val prefs = preferences(context)
        val entries = (0 until minOf(ids.length(), 500)).map(ids::optString).filter(id::matches).toSet()
        if (entries.isEmpty()) return
        val known = prefs.getStringSet("handled", emptySet()).orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (seen) {
            prefs.edit().putStringSet("handled", (known + entries).takeLastIds()).apply()
            manager.cancel(1052)
            return
        }
        if (!prefs.getBoolean("enabled", false) || !manager.areNotificationsEnabled() || entries.all(known::contains)) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ответы поддержки", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, 52, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setContentTitle("Ответ поддержки CORTEX")
            .setContentText("Откройте «Мои обращения», чтобы прочитать новые ответы.")
            .setSmallIcon(R.drawable.ic_cortex).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).build()
        runCatching { manager.notify(1052, notification) }.onSuccess {
            prefs.edit().putStringSet("handled", (known + entries).takeLastIds()).apply()
        }
    }

    private fun Set<String>.takeLastIds() = toList().takeLast(800).toSet()
}

class CortexSupportJob : JobService() {
    private var gateway: CortexServerBridge? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val server = CortexServerBridge(applicationContext)
        gateway = server
        server.inbox { outcome ->
            if (gateway !== server) return@inbox
            outcome.optJSONObject("result")?.optJSONArray("replies")?.let { replies ->
                val ids = JSONArray()
                for (i in 0 until replies.length()) replies.optJSONObject(i)?.optString("id")?.let(ids::put)
                CortexSupportNotification.update(applicationContext, ids)
            }
            gateway = null
            server.close()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        gateway?.close()
        gateway = null
        return true
    }

    override fun onDestroy() {
        gateway?.close()
        gateway = null
        super.onDestroy()
    }
}
