package app.cortex.assistant

internal data class NetworkRequest(val mode: Mode, val wifi: Boolean, val hotspot: Boolean) {
    enum class Mode { CHECK, REPAIR, ENABLE, DISABLE, OPEN }

    companion object {
        private val wifiName = Regex("""\b(?:wi ?fi|вай ?фай|вафля)\b""")
        private val hotspotName = Regex("""\b(?:модем\p{L}*|точк[ауие]\s+доступа|раздач\p{L}*|hotspot|tethering)\b""")
        private val phoneNetwork = Regex("""\b(?:интернет|сеть|подключение|internet)\b""")
        private val fault = Regex("""(?:выключа|отключа|отвали|пропада|обрыва|отключил|выключил|пропал|не\s+(?:работает|подключа|ловит)|переключа|сам включа|без интернета|нет интернета|keeps |disconnect|not working)""")
        private val repair = Regex("""\b(?:почини|исправь|устрани|восстанови|наладь|помоги|разберись|настрой|fix|repair|help)\b""")
        private val check = Regex("""\b(?:проверь|диагност\p{L}*|почему|что с|состояние|проведи проверку|diagnose|check|why|status)\b""")
        private val explain = Regex("""^(?:объясни|расскажи|подскажи|как |what |how |explain)""")
        private val foreign = Regex("""\b(?:на другом|на компьютере|на пк|роутер\p{L}*|маршрутизатор\p{L}*|на ноутбук\p{L}*)\b""")
        private val enable = Regex("""\b(?:включи(?:ть)?|активируй|enable|turn on)\b""")
        private val disable = Regex("""\b(?:выключи(?:ть)?|отключи(?:ть)?|disable|turn off)\b""")
        private val settings = Regex("""\b(?:открой|зайди в|покажи|open)\s+(?:настройки\s+)?""")
        private val direct = Regex("""^(?:(?:бро|пожалуйста|можешь) )*(?:включи(?:ть)?|активируй|enable|turn on|выключи(?:ть)?|отключи(?:ть)?|disable|turn off) (?:режим )?(?:wi ?fi|вай ?фай|вафля|модем(?:а)?|точку доступа|раздачу(?: интернета)?|hotspot|tethering)(?: пожалуйста)?$""")
        private val messaging = Regex("""\b(?:напиши|отправь|перешли|send)\b""")

        fun parse(raw: String): NetworkRequest? {
            val command = CortexHotword.command(VisualRequest.goal(raw)) ?: VisualRequest.goal(raw)
            val text = VisualLabels.normalize(command).removePrefix("бро ").removePrefix("пожалуйста ")
            if (explain.containsMatchIn(text) || foreign.containsMatchIn(text) || messaging.containsMatchIn(text)) return null
            val wifi = wifiName.containsMatchIn(text)
            val hotspot = hotspotName.containsMatchIn(text)
            val failing = fault.containsMatchIn(text)
            val generic = phoneNetwork.containsMatchIn(text) && (failing || check.containsMatchIn(text) || repair.containsMatchIn(text))
            if (!wifi && !hotspot && !generic) return null
            val mode = when {
                direct.matches(text) && enable.containsMatchIn(text) -> Mode.ENABLE
                direct.matches(text) && disable.containsMatchIn(text) -> Mode.DISABLE
                repair.containsMatchIn(text) -> Mode.REPAIR
                settings.containsMatchIn(text) && !failing -> Mode.OPEN
                failing || check.containsMatchIn(text) -> Mode.CHECK
                else -> return null
            }
            return NetworkRequest(mode, wifi || generic && !hotspot, hotspot)
        }
    }
}
