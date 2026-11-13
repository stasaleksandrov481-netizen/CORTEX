package app.cortex.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal class IslandAssistant(context: Context) : AutoCloseable {
    private val gateway = CortexServerBridge(context)
    private val phone = CortexPhoneActions(context)
    private val engine = CortexActionEngine(context)
    private val history = ArrayDeque<Pair<String, String>>()
    private var request: String? = null
    private var generation = 0L

    fun send(
        text: String, profile: String, currentPackage: String?, beforeScreen: () -> Unit,
        toolsAllowed: () -> Boolean, progress: (String) -> Unit, delta: (String) -> Unit,
        finished: (String, Boolean) -> Unit
    ) {
        if (request != null) return
        val turn = ++generation
        val tools = toolsAllowed()
        fun finish(answer: String, ok: Boolean) {
            if (turn != generation) return
            if (ok) remember(text, answer)
            finished(answer, ok)
        }
        if (tools && engine.start(text, profile, currentPackage, beforeScreen, progress, ::finish)) return
        val messages = JSONArray().put(message("system",
            "Ты CORTEX, голосовой помощник Android. Пиши живо, дружелюбно и по делу. " +
                "Представляйся только по просьбе. Продолжай разговор с учётом предыдущих реплик. " +
                "Отвечай на языке пользователя. Учитывай состояние телефона и выданные разрешения. " +
                (if (tools) """Для явно запрошенных действий вызови execute_phone_plan один раз со всеми шагами в исходном порядке.
                    Для действий внутри любого установленного приложения добавь screen_task с названием приложения и конкретной целью.
                    Открытие раздела, поиск, переход к переписке и ввод текста внутри приложения — задачи экрана.
                    Открытие приложения без дальнейших действий — app. Для выхода из него используй close_app.
                    Для фото добавь capture_photo, для выключения экрана — lock_screen. Если пользователь говорит «здесь», можно использовать текущее приложение.
                    Текущее приложение: """ + (currentPackage?.let(phone::resolveApp)?.name ?: "неизвестно") + ". "
                else "Сейчас доступен только разговор. ") +
                "Результат действия подтверждает Android. Данные устройства: " + phone.capabilities()))
        history.forEach { (input, output) -> messages.put(message("user", input)).put(message("assistant", output)) }
        messages.put(message("user", text))
        val route = if (profile == "deep") "deep" else "fast"
        val payload = JSONObject().put("messages", messages).put("style", "brief").put("profile", route)
        if (tools) payload.put("device_tools", true).put("action_plan", true)
        request = gateway.chat(payload, { event ->
            if (turn == generation && event.optString("status") == "fallback") progress("Пробую резервную модель…")
            if (turn == generation && event.has("delta") && !tools) delta(event.optString("delta"))
        }) { outcome ->
            if (turn != generation) return@chat
            request = null
            val error = outcome.optString("error")
            val result = outcome.optJSONObject("result")
            when {
                error.isNotEmpty() -> finish(errorMessage(error, outcome.optInt("retry_after")), false)
                result == null -> finish("Кортекс не получил ответ. Попробуйте ещё раз.", false)
                tools && result.has("tool_error") -> finish("Уточните действие или его параметры.", false)
                tools && (result.has("tool") || result.has("tools")) -> {
                    val commands = result.optJSONArray("tools") ?: JSONArray().put(result.optJSONObject("tool"))
                    if (!toolsAllowed()) {
                        finish("Управление устройством выключено.", false)
                        return@chat
                    }
                    val actions = (0 until commands.length()).mapNotNull(commands::optJSONObject)
                    if (actions.size != commands.length()) finish("Не удалось прочитать план действий.", false)
                    else engine.runPlan(actions, text, profile, beforeScreen, progress) { finish(it.text, it.ok) }
                }
                else -> {
                    val plain = result.optString("text").trim()
                    when {
                        tools && ACTION_CLAIM.containsMatchIn(plain) -> finish("Android пока не подтвердил действие.", false)
                        plain.isBlank() -> finish("Модель вернула пустой ответ.", false)
                        else -> finish(plain, true)
                    }
                }
            }
        }
    }

    fun cancel() {
        ++generation
        request?.let(gateway::cancel)
        request = null
        engine.cancel()
    }

    override fun close() {
        cancel()
        engine.close()
        gateway.close()
    }

    private fun remember(text: String, answer: String) {
        history.addLast(text to answer.take(2200))
        if (history.size > 5) history.removeFirst()
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun errorMessage(code: String, retryAfter: Int): String {
        val delay = if (retryAfter in 1..3600) " Повторите через " + ceil(retryAfter / 60.0).toInt() + " мин." else ""
        return when (code) {
            "provider_quota_daily" -> "Дневная квота бесплатных запросов OpenRouter исчерпана."
            "provider_quota", "provider_limit", "rate_limit" -> "Бесплатные модели сейчас перегружены." + delay
            "provider_key" -> "OpenRouter отклонил серверный ключ."
            "maintenance" -> "Идут технические работы. Статус доступен в приложении CORTEX."
            "update" -> "Для продолжения обновите CORTEX."
            "sign_in" -> "Для ИИ нужен вход в профиль CORTEX."
            "timeout" -> "Ответ занял слишком много времени."
            "offline" -> "Связь с сервером потеряна. Проверьте интернет."
            else -> "ИИ временно недоступен. Попробуйте ещё раз."
        }
    }

    private companion object {
        val ACTION_CLAIM = Regex("""(?iu)^(?:готово[,!.]?\s*)?(?:открыл|включил|выключил|запустил|установил|поставил|заблокировал)(?:\s|[.!]|$)""")
    }
}
