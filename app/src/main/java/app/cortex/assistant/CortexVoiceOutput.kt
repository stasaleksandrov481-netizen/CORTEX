package app.cortex.assistant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal object CortexVoiceOutput {
    private class Request(
        val owner: Any, val id: String, val text: String, val language: String, val speed: Float,
        val event: (String) -> Unit
    )
    private val main = Handler(Looper.getMainLooper())
    private var current: Request? = null
    private var local: LocalVoice? = null
    private var system: TextToSpeech? = null
    private var systemReady = false
    private var waitingForSystem = false
    private var generation = 0

    fun speak(context: Context, owner: Any, id: String, text: String, language: String,
              speed: Float, event: (String) -> Unit = {}) {
        val app = context.applicationContext
        val settings = VoiceStore.get(app)
        if (!settings.enabled) { event("muted"); return }
        val clean = text.trim().take(6000)
        if (clean.isBlank()) { event("done"); return }
        silence()
        val request = Request(owner, id, clean, language, speed.coerceIn(.6f, 1.5f), event)
        current = request
        CortexRuntime.speaking = true
        val pack = settings.selectedPack()?.takeIf { it.speaks(language) }
        val timeout = if (pack != null) 40_000L else 12_000L
        main.postDelayed({
            if (current === request && starting === request) stop(request, "error")
        }, timeout)
        starting = request
        if (pack == null) playSystem(app, request)
        else {
            local = LocalVoice.playback(app, pack, clean, request.speed, {
                if (current === request) started(request)
            }, { ok ->
                local = null
                if (current === request) {
                    if (!ok && starting === request) playSystem(app, request)
                    else finish(request, if (ok) "done" else "error")
                }
            })
            local?.start()
        }
    }

    private var starting: Request? = null

    private fun started(request: Request) {
        if (current !== request || starting !== request) return
        starting = null
        request.event("started")
    }

    private fun playSystem(context: Context, request: Request) {
        if (current !== request) return
        val tts = system
        if (tts == null) {
            val attempt = ++generation
            waitingForSystem = true
            system = TextToSpeech(context) { status ->
                main.post {
                    if (attempt != generation) return@post
                    systemReady = status == TextToSpeech.SUCCESS
                    if (waitingForSystem) {
                        waitingForSystem = false
                        current?.let { active ->
                            if (systemReady) playSystem(context, active)
                            else { resetSystem(); finish(active, "error") }
                        }
                    }
                }
            }
            return
        }
        if (!systemReady) { waitingForSystem = true; return }
        waitingForSystem = false
        try {
            val locale = Locale.forLanguageTag(request.language)
            if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                finish(request, "error")
                return
            }
            tts.setAudioAttributes(cortexSpeechAudio)
            tts.setPitch(1f)
            tts.setSpeechRate(request.speed)
            val utterances = speechChunks(request.text, TextToSpeech.getMaxSpeechInputLength().coerceIn(100, 3900))
            val token = (++generation).toString() + "-"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == token + 0) main.post { started(request) }
                }
                override fun onDone(id: String?) {
                    if (id == token + utterances.lastIndex) main.post { finish(request, "done") }
                }
                @Deprecated("Android legacy callback")
                override fun onError(id: String?) { error(id) }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String?, errorCode: Int) { error(id) }
                private fun error(id: String?) {
                    if (id?.startsWith(token) == true) main.post { if (current === request) stop(request, "error") }
                }
            })
            for ((index, chunk) in utterances.withIndex()) {
                val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (tts.speak(chunk, queue, Bundle(), token + index) != TextToSpeech.SUCCESS) {
                    stop(request, "error")
                    return
                }
            }
        } catch (_: Exception) { stop(request, "error") }
    }

    fun silence(owner: Any? = null, id: String? = null) {
        val request = current ?: return
        if ((owner == null || owner === request.owner) && (id == null || id == request.id)) stop(request, "stopped")
    }

    private fun stop(request: Request, phase: String) {
        local?.stop()
        local = null
        if (waitingForSystem && !systemReady) resetSystem()
        else system?.stop()
        finish(request, phase)
    }

    private fun resetSystem() {
        ++generation
        waitingForSystem = false
        systemReady = false
        system?.shutdown()
        system = null
    }

    private fun finish(request: Request, phase: String) {
        if (current !== request) return
        current = null
        starting = null
        CortexRuntime.speaking = false
        request.event(phase)
    }
}
