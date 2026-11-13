package app.cortex.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class CortexWakeService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var feed: WakeAudioFeed? = null
    private var feedGeneration = 0
    private var generation = 0
    private var enabled = false
    private var running = false
    private var checkedLanguage = false
    private var checkingLanguage = false
    private var requestedDownload = false
    private var streamed = false
    private var streamAvailable = Build.VERSION.SDK_INT >= 33
    private var streamRestarts = 0
    private var failures = 0
    private val rearm = Runnable { listen() }
    private val turn = CortexWakeTurn(
        listening = { CortexIslandService.send(this, CortexIslandService.ACTION_WAKE) },
        finished = { command ->
            CortexIslandService.send(this, CortexIslandService.ACTION_SUBMIT) {
                putExtra(CortexIslandService.EXTRA_MESSAGE, command)
            }
        },
        closed = { CortexIslandService.send(this, CortexIslandService.ACTION_WAKE_CLOSED) }
    )
    private val expire = Runnable { turn.expire(SystemClock.elapsedRealtime()) }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "CORTEX · голосовая активация", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 99, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("CORTEX · голосовая активация")
            .setContentText("Микрофон слушает слово «Кортекс» на устройстве")
            .setContentIntent(pending).setOngoing(true).build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(2811, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2811, notification)
        }.isSuccess
        if (!started || Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CortexRuntime.reportWake("Android не запустил офлайн-распознавание. Проверьте доступ к микрофону.")
            stopSelf()
            return
        }
        enabled = true
        CortexRuntime.wakeHook = ::updateAudio
        CortexRuntime.reportWake("Подключаю микрофон…")
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_250L)
        if (Build.VERSION.SDK_INT >= 33) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf("Кортекс", "Эй Кортекс", "Кортэкс"))
        }
    }

    private fun updateAudio() {
        if (CortexRuntime.audioBusy) {
            ui.removeCallbacks(rearm)
            ui.removeCallbacks(expire)
            turn.reset()
            closeFeed()
            destroyRecognizer()
            CortexRuntime.reportWake("Пауза на время диктовки или озвучивания.")
        } else schedule(110)
    }

    private fun listen() {
        if (!enabled || CortexRuntime.audioBusy || running || checkingLanguage || Build.VERSION.SDK_INT < 31) return
        if (!Settings.canDrawOverlays(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CortexRuntime.reportWake("Разрешите показ островка и доступ к микрофону.")
            stopSelf()
            return
        }
        val instance = recognizer ?: runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }
            .onSuccess { recognizer = it }.getOrNull()
        if (instance == null) {
            recover(false)
            return
        }
        val intent = recognitionIntent()
        if (Build.VERSION.SDK_INT >= 33 && !checkedLanguage) {
            checkLanguage(instance, intent)
            return
        }

        val useStream = Build.VERSION.SDK_INT >= 33 && streamAvailable
        if (useStream && !attachFeed(intent)) {
            fallback()
            return
        }
        val ticket = ++generation
        instance.setRecognitionListener(listener(ticket, useStream))
        running = true
        runCatching { instance.startListening(intent) }.onFailure {
            if (useStream) fallback() else recover(false)
        }
    }

    private fun attachFeed(intent: Intent): Boolean {
        return runCatching {
            val previous = feed
            val audio = previous ?: run {
                val ticket = ++feedGeneration
                WakeAudioFeed.start {
                    ui.post { if (enabled && feed != null && ticket == feedGeneration) fallback() }
                }.also { feed = it }
            }
            val read = audio.connect(replay = previous != null)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, WakeAudioFeed.ENCODING)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, WakeAudioFeed.RATE)
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }.isSuccess
    }

    private fun checkLanguage(instance: SpeechRecognizer, intent: Intent) {
        if (Build.VERSION.SDK_INT < 33) return
        checkingLanguage = true
        fun isRussian(tags: List<String>) = tags.any { it.equals("ru", true) || it.startsWith("ru-", true) || it.startsWith("ru_", true) }
        val timeout = Runnable {
            if (enabled && recognizer === instance && checkingLanguage) {
                checkingLanguage = false
                checkedLanguage = true
                schedule(0)
            }
        }
        ui.postDelayed(timeout, 3_200)
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (!enabled || recognizer !== instance || !checkingLanguage) return
                ui.removeCallbacks(timeout)
                checkingLanguage = false
                if (!isRussian(support.installedOnDeviceLanguages) &&
                    (isRussian(support.supportedOnDeviceLanguages) || isRussian(support.pendingOnDeviceLanguages))) {
                    if (!requestedDownload && !isRussian(support.pendingOnDeviceLanguages)) {
                        requestedDownload = true
                        runCatching { instance.triggerModelDownload(intent) }
                    }
                    CortexRuntime.reportWake("Android загружает русский язык для офлайн-распознавания.")
                    schedule(9_000)
                } else {
                    checkedLanguage = true
                    schedule(0)
                }
            }

            override fun onError(error: Int) = timeout.run()
        }
        runCatching { instance.checkRecognitionSupport(intent, mainExecutor, callback) }
            .onFailure { callback.onError(SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun listener(ticket: Int, useStream: Boolean) = object : RecognitionListener {
        private fun current() = enabled && running && ticket == generation && !CortexRuntime.audioBusy

        override fun onReadyForSpeech(params: Bundle?) {
            if (current()) CortexRuntime.reportWake("Слушаю слово «Кортекс».")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (current()) turn.partial(hypotheses(partialResults))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!current()) return
            streamed = true
            failures = 0
            streamRestarts = 0
            result(hypotheses(segmentResults), segmented = true)
        }

        override fun onEndOfSegmentedSession() {
            if (current()) restartStream()
        }

        override fun onResults(results: Bundle?) {
            if (!current()) return
            result(hypotheses(results))
            failures = 0
            if (useStream && !streamed) {
                // Some device recognizers ignore the audio source and return a regular session.
                fallback()
            } else if (useStream) restartStream()
            else {
                running = false
                schedule(70)
            }
        }

        override fun onError(error: Int) {
            if (!current()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    result(emptyList())
                    failures = 0
                    if (useStream) restartStream() else {
                        running = false
                        schedule(95)
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    CortexRuntime.reportWake("Разрешите CORTEX доступ к микрофону.")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    closeFeed()
                    destroyRecognizer()
                    CortexRuntime.reportWake("Установите русский язык для офлайн-распознавания Android.")
                    checkedLanguage = false
                    schedule(9_000)
                }
                else -> if (useStream && !streamed) fallback() else recover(useStream)
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun result(options: List<String>, segmented: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        turn.result(options, now, segmented)
        ui.removeCallbacks(expire)
        val remaining = turn.timeLeft(now)
        if (remaining > 0) ui.postDelayed(expire, remaining)
    }

    private fun restartStream() {
        if (!streamed && ++streamRestarts >= 2) {
            fallback()
            return
        }
        feed?.disconnect()
        running = false
        schedule(65)
    }

    private fun fallback() {
        streamAvailable = false
        closeFeed()
        destroyRecognizer()
        schedule(105)
    }

    private fun recover(useStream: Boolean) {
        failures = minOf(6, failures + 1)
        if (useStream) feed?.disconnect()
        destroyRecognizer()
        if (failures >= 3) CortexRuntime.reportWake("Восстанавливаю офлайн-распознавание…")
        schedule((270L * failures).coerceAtMost(1_620L))
    }

    private fun hypotheses(results: Bundle?): List<String> =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map(String::trim).filter(String::isNotEmpty)

    private fun schedule(delay: Long) {
        ui.removeCallbacks(rearm)
        if (enabled && !CortexRuntime.audioBusy) ui.postDelayed(rearm, delay)
    }

    private fun closeFeed() {
        ++feedGeneration
        feed?.close()
        feed = null
    }

    private fun destroyRecognizer() {
        ++generation
        running = false
        checkingLanguage = false
        recognizer?.let { instance -> runCatching { instance.cancel(); instance.destroy() } }
        recognizer = null
    }

    override fun onDestroy() {
        enabled = false
        CortexRuntime.wakeHook = null
        ui.removeCallbacksAndMessages(null)
        turn.reset()
        closeFeed()
        destroyRecognizer()
        if (CortexRuntime.wakeStatus?.startsWith("Слушаю") == true) CortexRuntime.reportWake(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object { const val CHANNEL = "cortex-listening" }
}
