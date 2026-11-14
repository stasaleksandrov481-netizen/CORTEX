package app.cortex.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** A single local microphone stream feeding the system's on-device recognizer. */
internal class WakeAudioFeed private constructor(
    private val recorder: AudioRecord,
    private val onFailure: () -> Unit,
    private val onLevel: (Float) -> Unit
) : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val connection = AtomicReference<Port?>()
    private val worker = Thread(::capture, "cortex-wake-audio").apply { start() }

    fun connect(replay: Boolean): ParcelFileDescriptor {
        check(open.get())
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val fd = pipe[1].fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        } catch (error: Exception) {
            pipe.forEach { runCatching { it.close() } }
            throw error
        }
        val port = Port(pipe[0], pipe[1], replay)
        connection.getAndSet(port)?.close()
        return port.read
    }

    fun disconnect() {
        connection.getAndSet(null)?.close()
    }

    private fun capture() {
        var failed = false
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val chunk = ByteArray(FRAME_BYTES)
            val tail = ArrayDeque<ByteArray>()
            val pending = ArrayDeque<ByteArray>()
            var previous: Port? = null
            var offset = 0
            var blockedFrames = 0
            var levelFrame = 0
            while (open.get()) {
                val count = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) throw IOException("Microphone input ended: $count")
                if (++levelFrame % 2 == 0) {
                    var energy = 0.0
                    for (i in 0 until count - 1 step 2) {
                        val sample = (chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)
                        energy += sample.toDouble() * sample
                    }
                    val rms = sqrt(energy / (count / 2).coerceAtLeast(1)) / 32768.0
                    onLevel((sqrt(rms) * 3.2).toFloat().coerceIn(0f, 1f))
                }
                val samples = chunk.copyOf(count)
                val port = connection.get()
                if (port !== previous) {
                    previous = port
                    pending.clear()
                    if (port?.replay == true) pending.addAll(tail)
                    offset = 0
                    blockedFrames = 0
                }
                if (tail.size >= REPLAY_FRAMES) tail.removeFirst()
                tail.addLast(samples)
                if (port == null) continue
                pending.addLast(samples)
                while (pending.size > MAX_PENDING_FRAMES) {
                    pending.removeFirst()
                    offset = 0
                }
                while (pending.isNotEmpty()) {
                    val data = pending.first()
                    val written = try {
                        Os.write(port.write.fileDescriptor, data, offset, data.size - offset)
                    } catch (error: ErrnoException) {
                        if (connection.get() !== port || !open.get()) break
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) break
                        throw error
                    }
                    if (written <= 0) break
                    offset += written
                    blockedFrames = 0
                    if (offset == data.size) {
                        pending.removeFirst()
                        offset = 0
                    }
                }
                if (pending.size == MAX_PENDING_FRAMES && ++blockedFrames > MAX_PENDING_FRAMES) {
                    throw IOException("The offline recognizer did not read the audio stream")
                }
            }
        } catch (_: Exception) {
            failed = open.get()
        } finally {
            val wasOpen = open.getAndSet(false)
            disconnect()
            if (wasOpen) runCatching { recorder.stop() }
            recorder.release()
            if (failed && wasOpen) onFailure()
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        disconnect()
        runCatching { recorder.stop() }
    }

    private class Port(val read: ParcelFileDescriptor, val write: ParcelFileDescriptor, val replay: Boolean) {
        fun close() {
            runCatching { write.close() }
            runCatching { read.close() }
        }
    }

    companion object {
        const val RATE = 16_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = 640
        private const val REPLAY_FRAMES = 24
        private const val MAX_PENDING_FRAMES = 80

        @SuppressLint("MissingPermission") // The foreground service checks the microphone permission before starting.
        fun start(onFailure: () -> Unit, onLevel: (Float) -> Unit): WakeAudioFeed {
            val channel = AudioFormat.CHANNEL_IN_MONO
            val minimum = AudioRecord.getMinBufferSize(RATE, channel, ENCODING)
            if (minimum <= 0) throw IOException("Microphone format unavailable")
            val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(channel).setEncoding(ENCODING).build()
            val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, 8 * FRAME_BYTES)).build()
            try {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                return WakeAudioFeed(recorder, onFailure, onLevel)
            } catch (error: Exception) {
                recorder.release()
                throw error
            }
        }
    }
}
