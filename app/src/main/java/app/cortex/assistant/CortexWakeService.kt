package app.cortex.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Experimental local wake recognizer. Built-in SpeechRecognizer isn't guaranteed to support
 * persistent 24/7 wake-word listening; Android/OEM battery and foreground restrictions apply.
 * Explicit opt-in + mic notification are mandatory. No audio is uploaded by this class.
 * Model implementation may be operated by the OS; prefer on-device recognizer only.
 */
class CortexWakeService : Service(), RecognitionListener {
    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var enabled = false
    private var restarting = false
    private var recent = 0L
    private var failures = 0
    private var awaitingCommandUntil = 0L
    private val rearm = Runnable { listen() }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("cortex-listening", "CORTEX · голосовая активация", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val notification = Notification.Builder(this, "cortex-listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("CORTEX слушает слово активации")
            .setContentText("Микрофон включён. Отключите в настройках CORTEX.")
            .setContentIntent(PendingIntent.getActivity(this, 99, open, flags))
            .setOngoing(true).build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(2811, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2811, notification)
        }.isSuccess
        if (!started) { stopSelf(); return }
        // Checked only after startForeground(): a service started with startForegroundService() must call it first.
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply { setRecognitionListener(this@CortexWakeService) }
        }.getOrNull()
        if (recognizer == null) { stopSelf(); return }
        enabled = true
        CortexRuntime.wakeHook = {
            runCatching { recognizer?.cancel() }
            ui.removeCallbacks(rearm); ui.postDelayed(rearm, 1500)
        }
        listen()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    private fun listen() {
        if (!enabled || restarting) return
        if (CortexRuntime.foreground) { // the open app owns the microphone
            ui.removeCallbacks(rearm); ui.postDelayed(rearm, 1500); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure { postpone() }
    }
    private fun postpone() {
        if (!enabled) return
        ui.removeCallbacks(rearm)
        failures++
        // Do not hammer the microphone on persistent API errors.
        if (failures > 20) { stopSelf(); return }
        ui.postDelayed(rearm, (1000L * failures).coerceAtMost(10_000L))
    }
    private fun accept(text: String) {
        val trimmed = text.trim()
        val now = SystemClock.elapsedRealtime()
        val match = Regex("^(?:кортекс|кортэкс|кортекст|картекс|кортикс|cortex)(?:[\\s,.:;!?-]+(.*))?$", RegexOption.IGNORE_CASE).find(trimmed)
        val command: String
        if (match != null) {
            if (now - recent < 1800L) return
            recent = now
            command = match.groupValues.getOrElse(1) { "" }.trim()
            // A brief, recognizable local confirmation without speech synthesis.
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 30)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 65)
                ui.postDelayed({ tone.release() }, 160)
            }
            awaitingCommandUntil = if (command.isEmpty()) now + 7000L else 0L
        } else {
            if (now > awaitingCommandUntil) return
            awaitingCommandUntil = 0L
            command = trimmed
        }
        failures = 0
        CortexIslandService.send(this,CortexIslandService.ACTION_STATUS) {
            putExtra(CortexIslandService.EXTRA_PHASE, if (command.isEmpty()) "listening" else "understood")
            putExtra(CortexIslandService.EXTRA_MESSAGE, command.take(64))
        }
        // Do not claim execution here. The AI tool layer must execute and confirm the command.
        // Expose a pending command through an explicit user-visible app/notification integration.
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("cortex_wake_command", command)
        }
        // Android 12+ restricts launching apps from background. A notification is required
        // on devices where this launch is disallowed; do not attempt to bypass the restriction.
        runCatching { startActivity(open) }
    }
    override fun onResults(results: Bundle?) {
        if (!CortexRuntime.foreground) results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::accept)
        ui.postDelayed(rearm, 900)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        // Waiting for the final recognition to avoid acting on false-positive partial phrases.
    }
    override fun onError(error: Int) { postpone() }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
    override fun onDestroy() {
        enabled = false
        CortexRuntime.wakeHook = null
        ui.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer=null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
