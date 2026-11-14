package app.cortex.assistant

import org.json.JSONObject
import java.io.File
import java.net.URI

internal class UnsupportedRuVoiceSpeaker : IllegalArgumentException()

internal object RuVoicePreset {
    private val stressWord = Regex("[а-яё]{1,40}\\+[а-яё]{0,40}")
    private val speakers = setOf("aidar", "baya", "eugene", "kseniya", "xenia")

    val russianModel = VoiceAsset(
        URI("https://github.com/kost-t-human/ruvoice-tts/releases/download/packs/ruvoice-pack-ru.zip"),
        86_495_507,
        "9b04263adc838d33050286cc3705bf0a4867605ed697e29b7db61531d2d82761",
        ""
    )

    fun defaults(voice: String): JSONObject {
        if (voice !in speakers) throw UnsupportedRuVoiceSpeaker()
        return JSONObject().put("schema", 1).put("engine", "ruvoice")
            .put("language", "ru-RU").put("profile", voice)
    }

    fun parse(file: File): JSONObject {
        require(file.length() in 2..1_500_000L)
        val export = JSONObject(file.readText())
        require(export.getString("app") == "ruvoice" && export.getInt("version") in 1..2)
        val prefs = export.optJSONObject("prefs") ?: JSONObject()
        val profiles = export.optJSONObject("profiles")
        val list = profiles?.optJSONArray("list")
        val active = profiles?.optString("active")
        val selected = (0 until (list?.length() ?: 0)).asSequence().mapNotNull { list?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == active }
        val profilePrefs = selected?.optJSONObject("prefs")
        fun value(name: String): Any? {
            val raw = profilePrefs?.opt(name) ?: prefs.opt(name)
            return if (raw is JSONObject) raw.opt("f") ?: raw.opt("i") ?: raw.opt("l") else raw
        }
        fun number(name: String, default: Double) = (value(name) as? Number)?.toDouble()
            ?.takeIf(Double::isFinite) ?: default
        val voice = (value("voice") as? String ?: selected?.optString("voice_bind")?.takeIf(String::isNotBlank) ?: "xenia")
            .removePrefix("ru/")
        val config = defaults(voice)
            .put("rate", number("rate", 1.0).coerceIn(.5, 3.0))
            .put("pitch", number("pitch", 1.0).coerceIn(0.0, 3.0))
            .put("volume", number("volume", 1.0).coerceIn(.5, 2.0))
            .put("sample_rate", if (number("sr", 24_000.0) == 48_000.0) 48_000 else 24_000)
            .put("max_len", number("max_len", 240.0).toInt().coerceIn(100, 400))
        for (key in listOf("pause_sentence", "pause_paragraph", "pause_comma", "pause_dash")) {
            config.put(key, number(key, 0.0).toInt().coerceIn(0, 1800))
        }
        val stress = JSONObject()
        val disabled = export.optJSONArray("stress_off")
        val ignored = (0 until (disabled?.length() ?: 0)).map { disabled?.optString(it) }.toSet()
        val dictionaries = export.optJSONObject("stress")
        val sources = dictionaries?.keys()?.asSequence()?.filterNot { it in ignored }
            ?.map { dictionaries.optString(it) } ?: sequenceOf(export.opt("stress") as? String ?: "")
        for (line in sources.flatMap { it.lineSequence() }.take(2500)) {
            val marked = line.substringBefore('#').trim().lowercase()
            if (marked.matches(stressWord)) {
                stress.put(marked.replace("+", ""), marked)
                if (stress.length() >= 800) break
            }
        }
        if (stress.length() > 0) config.put("stress", stress)
        return config
    }
}
