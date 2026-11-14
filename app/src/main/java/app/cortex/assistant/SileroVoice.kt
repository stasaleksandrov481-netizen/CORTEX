package app.cortex.assistant

import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.executorch.EValue
import java.util.Locale
import org.pytorch.executorch.Module as ExecModule
import org.pytorch.executorch.Tensor as ExecTensor

private val DIGITS = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

/** Runs the original RuVoice/Silero model files with the selected exported voice settings. */
internal class SileroVoice(private val pack: VoicePack) {
    private val model = JSONObject(pack.file("pack.json").readText())
    private val symbols = model.getJSONObject("symbol_to_id").let { table ->
        table.keys().asSequence().filter { it.length == 1 }.associate { it[0] to table.getInt(it).toLong() }
    }
    private val speaker = model.getJSONObject("speakers").getInt(pack.profile)
    private val start = symbols.getValue(model.getString("sos").single())
    private val end = symbols.getValue(model.getString("eos").single())
    private val rate = pack.settings.optInt("sample_rate", 24_000).takeIf { it == 48_000 } ?: 24_000
    private val maxLength = pack.settings.optInt("max_len", 240).coerceIn(100, 400)
    private val volume = pack.settings.optDouble("volume", 1.0).coerceIn(.5, 2.0).toFloat()
    private val prosody = mapOf(
        ',' to "pause_comma", '—' to "pause_dash", '–' to "pause_dash", '\n' to "pause_paragraph",
        '.' to "pause_sentence", '!' to "pause_sentence", '?' to "pause_sentence", '…' to "pause_sentence"
    )
    private val stress = pack.settings.optJSONObject("stress")
    private val number = Regex("(?<![0-9])[0-9]{1,12}(?![0-9])")
    private val word = Regex("[а-яё]+")

    fun synthesize(text: String, speed: Float, stopped: () -> Boolean, output: (Int, ShortArray) -> Boolean) {
        var mel: Module? = null
        var head: Module? = null
        var vocoder: ExecModule? = null
        try {
            val melModel = LiteModuleLoader.load(pack.file("tts_mel.ptl").path).also { mel = it }
            val headModel = LiteModuleLoader.load(pack.file("head.ptl").path).also { head = it }
            val backbone = ExecModule.load(pack.file("backbone.pte").path, ExecModule.LOAD_MODE_MMAP, 2).also { vocoder = it }
            val normalized = number.replace(text.lowercase(Locale.ROOT)) { it.value.map { digit -> DIGITS[digit - '0'] }.joinToString(" ") }
                .replace('\u0301', '+')
            val prepared = word.replace(normalized) { match -> stress?.optString(match.value, match.value) ?: match.value }
            val duration = 1f / speed.coerceIn(.5f, 3f)
            for ((line, separator) in phrases(prepared)) {
                for (part in speechChunks(line, maxLength)) {
                    if (stopped()) return
                    val ids = part.mapNotNull { char -> symbols[if (char == '—') '–' else char] }
                    if (ids.size < 2) continue
                    val seq = (listOf(start) + ids + end).toLongArray()
                    val audio = generate(melModel, headModel, backbone, seq, duration, stopped)
                    if (stopped()) return
                    if (audio.isNotEmpty() && !output(rate, audio.toPcm(volume))) return
                }
                val pause = pack.settings.optInt(prosody[separator].orEmpty()).coerceIn(0, 1800)
                if (!stopped() && pause > 0 && !output(rate, ShortArray(rate * pause / 1000))) return
            }
        } finally {
            vocoder?.close()
            head?.destroy()
            mel?.destroy()
        }
    }

    private fun phrases(text: String): List<Pair<String, Char?>> {
        val output = mutableListOf<Pair<String, Char?>>()
        var start = 0
        for ((i, char) in text.withIndex()) {
            val key = prosody[char] ?: continue
            if (pack.settings.optInt(key) <= 0 && char !in ".!?…\n") continue
            val part = text.substring(start, i + 1).trim()
            if (part.isNotEmpty()) output += part to char
            start = i + 1
        }
        if (start < text.length) output += text.substring(start).trim() to null
        return output
    }

    private fun generate(mel: Module, head: Module, vocoder: ExecModule, seq: LongArray,
                         duration: Float, stopped: () -> Boolean): FloatArray {
        val shape = longArrayOf(1, seq.size.toLong())
        val inputs = mutableListOf(
            IValue.from(Tensor.fromBlob(seq, shape)),
            IValue.from(Tensor.fromBlob(longArrayOf(speaker.toLong()), longArrayOf(1))),
            IValue.from(rate.toLong()),
            IValue.optionalNull(),
            IValue.from(Tensor.fromBlob(FloatArray(seq.size) { duration }, shape)),
            IValue.from(Tensor.fromBlob(FloatArray(seq.size) { pack.pitch }, shape)),
            IValue.optionalNull(),
            IValue.optionalNull(),
            IValue.from("cpu"),
            IValue.from(-1L),
            IValue.from(false)
        )
        if (model.optBoolean("types")) {
            inputs += IValue.from(Tensor.fromBlob(LongArray(seq.size), shape))
            inputs += IValue.optionalNull()
        }
        val spectrum = mel.forward(*inputs.toTypedArray()).toTuple()[0].toTensor()
        if (stopped()) return FloatArray(0)
        val features = vocoder.forward(EValue.from(ExecTensor.fromBlob(spectrum.dataAsFloatArray, spectrum.shape())))[0].toTensor()
        if (stopped()) return FloatArray(0)
        return head.forward(
            IValue.from(Tensor.fromBlob(features.dataAsFloatArray, features.shape())),
            IValue.from(rate.toLong()), IValue.from(0.0), IValue.from(true)
        ).toTensor().dataAsFloatArray
    }
}
