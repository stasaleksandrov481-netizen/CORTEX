package app.cortex.assistant

import org.json.JSONObject
import java.util.Locale

internal object CortexLocalCommands {
    private val separator = Regex("""\s*(?:[,;]\s*(?:и\s+)?|\s+и\s+(?:потом\s+)?|\s+затем\s+)\s*""", RegexOption.IGNORE_CASE)
    private val volume = Regex("""(?:установи|поставь|сделай|измени) громкость (?:на )?(\d{1,3})(?:\s*(?:%|процент(?:а|ов)?))?""")
    private val store = Regex("""(?:найди|поищи|покажи|установи|скачай|открой)\s+(.+?)\s+в\s+(?:google play|play market|play маркет(?:е)?|плей маркет(?:е)?|гугл плее)""", RegexOption.IGNORE_CASE)
    private val launch = Regex("""(?:открой|запусти|включи приложение) (?:приложение )?(.{2,65})""")
    private val instruction = Regex("""^(?:как|почему|можно ли|что |зачем|объясни|подскажи как)\b""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, phone: CortexPhoneActions): List<JSONObject>? {
        val request = raw.trim().trimEnd('.', '!', '?').replace(Regex("""(?iu)^кортекс[, ]+\s*"""), "")
        if (request.isBlank() || raw.trim().endsWith("?") && instruction.containsMatchIn(request)) return null
        store.matchEntire(request)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 1..100 }?.let {
            return listOf(command("play_store", "query" to it))
        }
        val parts = request.split(separator)
        if (parts.size !in 1..6) return null
        return parts.map { part -> parseOne(part.trim().lowercase(Locale.ROOT).replace('ё', 'е'), phone) ?: return null }
    }

    private fun parseOne(text: String, phone: CortexPhoneActions): JSONObject? {
        val plain = text.removePrefix("пожалуйста ").removeSuffix(" пожалуйста")
        return when (plain) {
            "включи фонарик", "зажги фонарик" -> command("torch", "mode" to "on")
            "выключи фонарик", "погаси фонарик", "отключи фонарик" -> command("torch", "mode" to "off")
            "переключи фонарик" -> command("torch", "mode" to "toggle")
            "убери громкость", "выключи звук", "отключи звук", "убери звук", "поставь без звука" ->
                command("volume", "mode" to "mute", "stream" to "media")
            "прибавь громкость", "увеличь громкость", "сделай погромче" -> command("volume", "mode" to "up")
            "убавь громкость", "уменьши громкость", "сделай потише" -> command("volume", "mode" to "down")
            "который час", "который сейчас час", "сколько сейчас времени", "сколько времени", "скажи время" ->
                command("read_device", "target" to "time")
            "сколько заряда", "сколько процентов заряда", "проверь заряд", "сколько заряда осталось", "покажи заряд" ->
                command("read_device", "target" to "battery")
            "сверни приложения", "сверни приложение", "на главный экран", "открой рабочий стол", "сверни все приложения" ->
                command("home")
            "открой недавние", "покажи недавние приложения", "открой список окон" -> command("recents")
            "заблокируй экран", "заблокируй телефон", "погаси экран" -> command("lock_screen")
            "открой настройки vpn", "включи vpn", "выключи vpn" -> command("settings", "target" to "vpn")
            "открой настройки wi-fi", "включи wi-fi", "выключи wi-fi", "включи вай фай", "выключи вай-фай" ->
                command("panel", "target" to "wifi")
            "открой настройки" -> command("settings", "target" to "system")
            "тг", "телега", "телеграм", "telegram" -> command("app", "target" to "telegram")
            "тт", "тикток", "tiktok" -> command("app", "target" to "tiktok")
            else -> {
                val level = volume.matchEntire(plain)?.groupValues?.get(1)?.toIntOrNull()
                if (level != null && level in 0..100) return command("volume", "mode" to "set", "level" to level)
                val target = launch.matchEntire(plain)?.groupValues?.get(1) ?: return null
                val app = phone.resolveApp(target) ?: return null
                command("app", "target" to app.pkg)
            }
        }
    }

    private fun command(kind: String, vararg pairs: Pair<String, Any>) = JSONObject().put("kind", kind).apply {
        pairs.forEach { (name, value) -> put(name, value) }
    }
}
