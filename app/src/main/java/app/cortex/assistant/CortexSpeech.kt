package app.cortex.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/** Voice input while the app is foregrounded; this is not a background hotword service. */
class CortexSpeech(private val activity: Activity, private val emit: (JSONObject) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var activeId: String? = null
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
        CortexVoiceOutput.speak(activity, this, id, text, language, speed) { phase -> speech(id, phase) }
    }

    fun silence(id: String?) = CortexVoiceOutput.silence(this, id)

    fun close() {
        stop()
        silence(null)
        ui.removeCallbacksAndMessages(null)
    }
}
