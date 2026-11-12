package app.cortex.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

internal class IslandAssistant(context: Context) : AutoCloseable {
    private val gateway = CortexServerBridge(context)
    private val phone = CortexPhoneActions(context)
    private val history = ArrayDeque<Pair<String, String>>()
    private var request: String? = null

    fun send(
        text: String,
        profile: String,
        toolsAllowed: () -> Boolean,
        progress: (String) -> Unit,
        delta: (String) -> Unit,
        finished: (String, Boolean) -> Unit
    ) {
        if (request != null) return
        val tools = toolsAllowed()
        if (tools) localCommand(text)?.let { command ->
            progress("Выполняю…")
            val result = execute(command)
            if (result.ok) remember(text, result.text)
            finished(result.text, result.ok)
            return
        }
        val messages = JSONArray().put(turn("system",
            "Ты Кортекс, ассистент Android. Это компактный островок поверх приложений. " +
                "Отвечай на языке пользователя, кратко и простым текстом. " +
                "Вызывай инструменты только для явно запрошенных действий. Уточняй неоднозначные команды. " +
                "О выполнении сообщит результат Android."))
        history.forEach { (input, output) ->
            messages.put(turn("user", input)).put(turn("assistant", output))
        }
        messages.put(turn("user", text))
        val route = if (profile == "auto") "fast" else profile
        val payload = JSONObject().put("messages", messages).put("style", "brief").put("profile", route)
        if (tools) payload.put("device_tools", true)
        request = gateway.chat(payload, { event ->
            if (event.optString("status") == "fallback") progress("Пробую резервную модель…")
            if (event.has("delta")) delta(event.optString("delta"))
        }) { outcome ->
            request = null
            val error = outcome.optString("error")
            val result = outcome.optJSONObject("result")
            val answer = when {
                error.isNotEmpty() -> Result(errorMessage(error, outcome.optInt("retry_after")), false)
                result == null -> Result("Кортекс не получил ответ. Попробуйте ещё раз.", false)
                tools && result.has("tool_error") -> Result("Уточните действие или его параметры.", false)
                tools && (result.has("tool") || result.has("tools")) -> if (toolsAllowed()) {
                    progress("Выполняю…")
                    runActions(result)
                } else Result("Управление устройством выключено.", false)
                else -> {
                    val plain = result.optString("text").trim()
                    if (tools && ACTION_CLAIM.containsMatchIn(plain)) {
                        Result("Android не подтвердил действие. Уточните команду и попробуйте ещё раз.", false)
                    } else if (plain.isBlank()) Result("Модель вернула пустой ответ.", false)
                    else Result(plain, true)
                }
            }
            if (answer.ok) remember(text, answer.text)
            finished(answer.text, answer.ok)
        }
    }

    fun cancel() {
        request?.let(gateway::cancel)
        request = null
    }

    override fun close() {
        cancel()
        gateway.close()
    }

    private fun remember(text: String, answer: String) {
        history.addLast(text to answer.take(2200))
        if (history.size > 5) history.removeFirst()
    }

    private fun runActions(response: JSONObject): Result {
        val commands = response.optJSONArray("tools") ?: JSONArray().put(response.optJSONObject("tool"))
        val reports = mutableListOf<String>()
        val executed = mutableSetOf<String>()
        var success = true
        for (index in 0 until minOf(commands.length(), 4)) {
            val command = commands.optJSONObject(index) ?: continue
            command.remove("next")
            if (!executed.add(command.toString())) continue
            val outcome = execute(command)
            reports.add(outcome.text)
            success = success && outcome.ok
            if (!outcome.ok || outcome.external) break
        }
        return if (reports.isEmpty()) Result("Уточните команду.", false)
        else Result(reports.joinToString("\n"), success)
    }

    private fun execute(command: JSONObject): Result {
        val outcome = runCatching { phone.execute(command) }.getOrElse { JSONObject().put("status", "unavailable") }
        val kind = command.optString("kind")
        val target = command.optString("target")
        return when (outcome.optString("status")) {
            "opened" -> Result(when (kind) {
                "app" -> "Команда открыть «${outcome.optString("name", target)}» передана Android."
                "timer" -> "Таймер передан в «Часы»."
                "alarm" -> "Будильник передан в «Часы»."
                "dial" -> "Номер передан в приложение для звонков."
                "sms" -> "Черновик передан приложению SMS."
                else -> "Android получил команду открытия."
            }, true, true)
            "panel" -> Result("Открыты настройки Android. Выберите нужный переключатель.", true, true)
            "done" -> Result(when (kind) {
                "read_device" -> readout(target, outcome)
                "volume" -> "Громкость — ${outcome.optInt("level")}%."
                "brightness" -> "Яркость экрана — ${outcome.optInt("level")}%."
                "torch" -> if (outcome.optBoolean("enabled")) "Фонарик включён." else "Фонарик выключен."
                "app_list" -> outcome.optJSONArray("names")?.let { names ->
                    (0 until minOf(names.length(), 14)).joinToString(", ") { names.optString(it) }
                }.orEmpty().ifBlank { "Приложения не найдены." }
                "copy_text" -> "Текст скопирован."
                "lock_screen" -> "Экран заблокирован."
                else -> "Действие выполнено."
            }, true, kind == "lock_screen")
            "not_installed" -> Result("Приложение «$target» не найдено на телефоне.", false)
            "needs_brightness" -> Result("Разрешите CORTEX менять яркость в настройках Android.", false)
            "needs_lock" -> Result("Для блокировки экрана нужен доступ в разделе «Вызов».", false)
            "unsupported" -> Result("Это действие недоступно на устройстве.", false)
            else -> Result("Android не смог выполнить команду.", false)
        }
    }

    private fun readout(target: String, result: JSONObject): String = when (target) {
        "battery" -> {
            val level = result.optInt("level", -1)
            if (level !in 0..100) "Android не сообщил уровень заряда."
            else "Заряд батареи — $level%." + if (result.optBoolean("charging")) " Телефон заряжается." else ""
        }
        "storage" -> String.format(Locale.forLanguageTag("ru-RU"), "Свободно %.1f ГБ.", result.optLong("free") / 1073741824.0)
        "time" -> "Сейчас " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + "."
        "wifi" -> if (result.optBoolean("enabled")) "Wi-Fi включён." else "Wi-Fi выключен."
        else -> if (result.has("enabled")) {
            if (result.optBoolean("enabled")) "Включено." else "Выключено."
        } else result.optString("value", "Android не сообщил состояние.")
    }

    private fun turn(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    private fun command(kind: String, vararg args: Pair<String, Any>) = JSONObject().put("kind", kind).apply {
        args.forEach { (name, value) -> put(name, value) }
    }

    private fun localCommand(raw: String): JSONObject? {
        val text = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim().trimEnd('.', '!', '?', ' ').replace(Regex("""\s+"""), " ")
        return when (text) {
            "включи фонарик", "включи фонарь", "включить фонарик", "turn on the flashlight", "turn on flashlight" -> command("torch", "mode" to "on")
            "выключи фонарик", "выключи фонарь", "выключить фонарик", "turn off the flashlight", "turn off flashlight" -> command("torch", "mode" to "off")
            "battery level", "check battery level", "сколько заряда", "сколько процентов заряда", "какой заряд батареи", "какой заряд телефона", "сколько зарядки", "проверь заряд", "покажи заряд батареи" ->
                command("read_device", "target" to "battery")
            "сколько времени", "который час", "который сейчас час", "сколько сейчас времени", "скажи который час" ->
                command("read_device", "target" to "time")
            "открой телеграм", "открой телеграмм", "открой telegram", "открой тг", "открой телегу" -> command("app", "target" to "telegram")
            "открой ютуб", "открой youtube" -> command("app", "target" to "youtube")
            "открой настройки wi-fi", "открой настройки вай фай", "открой настройки вай-фай" -> command("settings", "target" to "wifi")
            else -> VOLUME.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
                ?.let { command("volume", "stream" to "media", "mode" to "set", "level" to it) }
        }
    }

    private fun errorMessage(code: String, retryAfter: Int): String {
        val delay = if (retryAfter in 1..3600) " Повторите через ${ceil(retryAfter / 60.0).toInt()} мин." else ""
        return when (code) {
            "provider_quota_daily" -> "Дневная квота бесплатных запросов OpenRouter исчерпана. Она обновится в полночь UTC."
            "provider_quota" -> "OpenRouter временно ограничил бесплатные запросы." + delay
            "provider_key" -> "OpenRouter отклонил серверный ключ. Нужна проверка подключения."
            "provider_budget", "provider_balance" -> "На стороне OpenRouter исчерпан бюджет или баланс."
            "provider_limit", "provider", "provider_access", "model" -> "Бесплатные модели сейчас недоступны. Попробуйте чуть позже." + delay
            "rate_limit" -> "CORTEX получил слишком много запросов. Попробуйте позже."
            "sign_in" -> "Для ИИ нужен вход в профиль CORTEX."
            "timeout" -> "Ответ занял слишком много времени. Попробуйте ещё раз."
            "offline" -> "Связь с сервером потеряна. Проверьте интернет."
            else -> "ИИ временно недоступен. Попробуйте ещё раз."
        }
    }

    private data class Result(val text: String, val ok: Boolean, val external: Boolean = false)

    private companion object {
        val VOLUME = Regex("""(?:установи|поставь|сделай) громкость (?:на )?(\d{1,3})(?:\s*(?:%|процент(?:а|ов)?))?""")
        val ACTION_CLAIM = Regex("""^(?:готово[,!.]?\s*)?(?:открыл|включил|выключил|запустил|установил|поставил|заблокировал)\b""",
            RegexOption.IGNORE_CASE)
    }
}
