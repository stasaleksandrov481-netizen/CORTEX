package app.cortex.assistant

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private const val VALIDATE = 1
private const val PLAY = 2
private const val STOP = 3
private const val STARTED = 4
private const val DONE = 5
private const val FAILED = 6

internal class LocalVoice(
    private val context: Context, private val pack: VoicePack, private val text: String = "",
    private val speed: Float = 1f, private val event: (Int) -> Unit
) : ServiceConnection {
    private val main = Handler(Looper.getMainLooper())
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private val timeout = Runnable { finish(FAILED) }
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!closed) {
            if (message.what == STARTED) event(STARTED)
            else finish(message.what)
        }
        true
    })

    fun start() {
        bound = runCatching {
            context.bindService(Intent(context, VoiceSynthesisService::class.java), this, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) main.postDelayed(timeout, if (text.isEmpty()) 80_000 else 240_000)
        else finish(FAILED)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (closed) return
        remote = Messenger(service)
        val message = Message.obtain(null, if (text.isEmpty()) VALIDATE else PLAY).apply {
            replyTo = reply
            data = Bundle().apply {
                putString("path", pack.directory.absolutePath)
                putString("text", text)
                putFloat("speed", speed)
            }
        }
        if (runCatching { remote?.send(message) }.isFailure) finish(FAILED)
    }

    override fun onServiceDisconnected(name: ComponentName) = finish(FAILED)
    override fun onBindingDied(name: ComponentName) = finish(FAILED)
    override fun onNullBinding(name: ComponentName) = finish(FAILED)

    fun stop() {
        if (closed) return
        runCatching { remote?.send(Message.obtain(null, STOP).apply { replyTo = reply }) }
        close()
    }

    private fun finish(result: Int) {
        if (closed) return
        if (result == FAILED) stop() else close()
        event(result)
    }

    private fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        if (bound) runCatching { context.unbindService(this) }
        remote = null
    }

    companion object {
        fun validate(context: Context, pack: VoicePack) {
            val main = Handler(Looper.getMainLooper())
            val done = CountDownLatch(1)
            var valid = false
            val voice = LocalVoice(context, pack) { result ->
                valid = result == DONE
                done.countDown()
            }
            main.post(voice::start)
            try {
                check(done.await(85, TimeUnit.SECONDS) && valid) { "Voice validation failed" }
            } finally { main.post(voice::stop) }
        }

        fun playback(
            context: Context, pack: VoicePack, text: String, speed: Float,
            started: () -> Unit, finished: (Boolean) -> Unit
        ) = LocalVoice(context, pack, text, speed) { result ->
            if (result == STARTED) started() else finished(result == DONE)
        }
    }
}

/** The native engines run in a private process so a bad model can't close the CORTEX UI. */
class VoiceSynthesisService : Service() {
    private class Job {
        @Volatile var stopped = false
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val jobs = mutableMapOf<IBinder, Job>()
    private val main = Handler(Looper.getMainLooper())
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo ?: return@Handler true
        val key = reply.binder
        if (message.what == STOP) {
            jobs.remove(key)?.stopped = true
            return@Handler true
        }
        if (message.what != PLAY && message.what != VALIDATE) return@Handler true
        val validation = message.what == VALIDATE
        val path = message.data.getString("path").orEmpty()
        val text = message.data.getString("text").orEmpty().take(6000)
        val speed = message.data.getFloat("speed", 1f).coerceIn(.6f, 1.5f)
        val job = Job()
        jobs.put(key, job)?.stopped = true
        worker.execute {
            fun send(type: Int) {
                if (!job.stopped) runCatching { reply.send(Message.obtain(null, type)) }
            }
            if (!job.stopped) {
                val ok = runCatching {
                    val root = File(filesDir, "voices").canonicalPath + File.separator
                    val directory = File(path).canonicalFile
                    require(directory.path.startsWith(root))
                    val pack = VoicePack.read(directory)
                    val input = if (validation) {
                        if (pack.locale.language == "ru") "Голос готов." else "123."
                    } else text
                    var hasAudio = false
                    if (validation) {
                        synthesize(pack, input, speed, { job.stopped }) { _, samples ->
                            hasAudio = hasAudio || samples.any { abs(it.toInt()) > 10 }
                            !job.stopped
                        }
                    } else {
                        VoiceAudio({ job.stopped }) { send(STARTED) }.use { output ->
                            synthesize(pack, input, speed, { job.stopped }) { rate, samples ->
                                hasAudio = hasAudio || samples.isNotEmpty()
                                output.write(rate, samples)
                            }
                            if (hasAudio) output.drain()
                        }
                    }
                    check(hasAudio)
                }.isSuccess
                send(if (ok) DONE else FAILED)
            }
            main.post { if (jobs[key] === job) jobs.remove(key) }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        jobs.values.forEach { it.stopped = true }
        jobs.clear()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun synthesize(
        pack: VoicePack, text: String, speed: Float, stopped: () -> Boolean,
        output: (Int, ShortArray) -> Boolean
    ) {
        val rate = (pack.rate * speed).coerceIn(.5f, 3f)
        if (pack.engine == "ruvoice") {
            SileroVoice(pack).synthesize(text, rate, stopped, output)
        } else {
            val vits = OfflineTtsVitsModelConfig(
                model = pack.file("model.onnx").path,
                tokens = pack.file("tokens.txt").path,
                dataDir = pack.file("espeak-ng-data").takeIf(File::isDirectory)?.path.orEmpty(),
                lexicon = pack.file("lexicon.txt").takeIf(File::isFile)?.path.orEmpty()
            )
            val engine = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2)))
            try {
                check(pack.speaker < engine.numSpeakers().coerceAtLeast(1))
                for (chunk in speechChunks(text, 220)) {
                    if (stopped()) break
                    val audio = engine.generate(chunk, sid = pack.speaker, speed = rate)
                    val samples = audio.samples.toPcm()
                    if (samples.isNotEmpty() && !output(audio.sampleRate, samples)) break
                }
            } finally { engine.release() }
        }
    }
}

internal fun FloatArray.toPcm(volume: Float = 1f) = ShortArray(size) { index ->
    val value = this[index] * volume
    if (value.isFinite()) (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() else 0
}

internal val cortexSpeechAudio: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal fun speechChunks(text: String, limit: Int): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val bound = minOf(start + limit, text.length)
        val natural = if (bound == text.length) bound else (bound - 1 downTo start + limit / 2).firstOrNull {
            text[it].isWhitespace()
        } ?: bound
        val end = if (natural < text.length && Character.isLowSurrogate(text[natural])) natural - 1 else natural
        text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        start = end
    }
    return chunks
}

private class VoiceAudio(private val stopped: () -> Boolean, private val started: () -> Unit) : AutoCloseable {
    private var track: AudioTrack? = null
    private var rate = 0
    private var written = 0L
    private var began = false

    fun write(sampleRate: Int, samples: ShortArray): Boolean {
        if (samples.isEmpty() || stopped()) return !stopped()
        if (track == null || rate != sampleRate) {
            drain()
            close()
            require(sampleRate in 8_000..96_000)
            rate = sampleRate
            written = 0
            val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(size > 0)
            track = AudioTrack.Builder().setAudioAttributes(cortexSpeechAudio)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(size, rate / 5)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }
        val output = checkNotNull(track)
        var at = 0
        while (at < samples.size && !stopped()) {
            val count = output.write(samples, at, minOf(rate / 20, samples.size - at), AudioTrack.WRITE_NON_BLOCKING)
            check(count >= 0)
            at += count
            written += count
            if (written > 0 && output.playState != AudioTrack.PLAYSTATE_PLAYING) {
                output.play()
                if (!began) { began = true; started() }
            }
            if (count == 0) SystemClock.sleep(12)
        }
        return !stopped()
    }

    fun drain() {
        val output = track ?: return
        val timeout = SystemClock.elapsedRealtime() + 2_500
        while (!stopped() && output.playbackHeadPosition.toLong() < written && SystemClock.elapsedRealtime() < timeout) {
            SystemClock.sleep(15)
        }
    }

    override fun close() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        track = null
    }
}
