package app.cortex.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale

/** Voice input while the app is foregrounded; this is not a background hotword service. */
class CortexSpeech(private val activity: Activity, private val emit: (JSONObject) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var activeId: String? = null
    private var utteranceId: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var ttsPending: Pair<String, () -> Unit>? = null

    private fun voice(id: String, phase: String, text: String? = null, level: Double? = null) {
        val e = JSONObject().put("type", "voice").put("id", id).put("phase", phase)
        if (text != null) e.put("text", text)
        if (level != null) e.put("level", level)
        emit(e)
    }
    private fun speech(id: String, phase: String) { emit(JSONObject().put("type", "speech").put("id", id).put("phase", phase)) }

    fun listen(id: String, language: String) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            voice(id, "error", "На устройстве нет доступного распознавания речи.")
            return
        }
        activeId = id
        CortexRuntime.recording = true
        if (CortexRuntime.wakeHook != null) ui.postDelayed({ beginListening(id, language) }, 200)
        else beginListening(id, language)
    }

    private fun beginListening(id: String, language: String) {
        if (activeId != id) return
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = voice(id, "ready")
                override fun onBeginningOfSpeech() = voice(id, "hearing")
                override fun onRmsChanged(rmsdB: Float) = voice(id, "level", level = ((rmsdB + 2) / 16).coerceIn(0f, 1f).toDouble())
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = voice(id, "finishing")
                override fun onError(error: Int) {
                    if (activeId == id) { stop(); voice(id, "error", "Распознавание речи не завершилось. Попробуйте снова.") }
                }
                override fun onResults(results: Bundle?) {
                    if (activeId == id) {
                        stop()
                        voice(id, "result", results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (activeId == id) voice(id, "partial", partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_250L)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            r.startListening(intent)
        } catch (_: Exception) {
            stop()
            voice(id, "error", "Android не разрешил запустить микрофон.")
        }
    }

    fun finish(id: String) { if (activeId == id) recognizer?.stopListening() }
    fun cancel(id: String?) { if (id == null || activeId == id) stop() }
    fun stop() {
        activeId = null
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        CortexRuntime.recording = false
    }

    fun speak(id: String, text: String, language: String, speed: Float) {
        if (tts == null) {
            ttsInitializing = true
            ttsPending = id to { speak(id, text, language, speed) }
            tts = TextToSpeech(activity.applicationContext) { status ->
                ui.post {
                    if (!ttsInitializing) return@post
                    ttsInitializing = false
                    ttsReady = status == TextToSpeech.SUCCESS
                    val next = ttsPending
                    ttsPending = null
                    next?.second?.invoke()
                }
            }
            return
        }
        if (ttsInitializing) {
            ttsPending = id to { speak(id, text, language, speed) }
            return
        }
        if (!ttsReady || tts == null) { speech(id, "error"); return }
        try {
            tts?.stop()
            utteranceId = id
            CortexRuntime.speaking = true
            tts?.language = Locale.forLanguageTag(language)
            tts?.setSpeechRate(speed.coerceIn(0.6f, 1.5f))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { if (utteranceId == id) ui.post { speech(id, "started") } }
                override fun onDone(utteranceId: String?) { if (utteranceId == id) ui.post { finishSpeech(id, "done") } }
                @Deprecated("System TTS error callback") override fun onError(utteranceId: String?) { if (utteranceId == id) ui.post { finishSpeech(id, "error") } }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) ui.post { finishSpeech(id, "error") } }
            })
            val result = tts?.speak(text.take(4500), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) finishSpeech(id, "error")
        } catch (_: Exception) { finishSpeech(id, "error") }
    }
    fun silence(id: String?) {
        val waiting = ttsPending
        if (waiting != null && (id == null || id == waiting.first)) {
            ttsPending = null
            speech(waiting.first, "stopped")
        }
        val current = utteranceId ?: return
        if (id != null && id != current) return
        tts?.stop()
        finishSpeech(current, "stopped")
    }

    private fun finishSpeech(id: String, phase: String) {
        if (utteranceId != id) return
        utteranceId = null
        CortexRuntime.speaking = false
        speech(id, phase)
    }

    fun close() {
        stop()
        silence(null)
        ttsInitializing = false
        tts?.shutdown()
        tts = null
        ui.removeCallbacksAndMessages(null)
    }
}
