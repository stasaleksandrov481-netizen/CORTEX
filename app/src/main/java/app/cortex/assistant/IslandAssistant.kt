package app.cortex.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal class IslandAssistant(private val context: Context) : AutoCloseable {
    private val gateway = CortexServerBridge(context)
    private val engine = CortexActionEngine(context)
    private val history = ArrayDeque<Pair<String, String>>()
    private var request: String? = null
    private var generation = 0L

    fun send(
        text: String, profile: String, beforeScreen: () -> Unit,
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
        if (engine.start(text, profile, allowPhone = tools, beforeScreen = beforeScreen, progress = progress) { result ->
                if (turn == generation) finished(result.text, result.ok)
            }) return
        val messages = JSONArray().put(message("system",
            CortexDeviceContext.IDENTITY + " Ответь кратко и на языке пользователя. " +
                "Это разговорный ответ. Утверждай, что действие выполнено, только если локальный движок уже его подтвердил. " +
                "Возможности сейчас: " + CortexDeviceContext.runtime(context, CortexScreenService.connected, tools)))
        history.forEach { (input, output) -> messages.put(message("user", input)).put(message("assistant", output)) }
        messages.put(message("user", text))
        val route = if (profile == "deep") "deep" else "fast"
        val payload = JSONObject().put("messages", messages).put("style", "brief").put("profile", route)
        request = gateway.chat(payload, { event ->
            if (turn == generation && event.optString("status") == "fallback") progress("Пробую резервную модель…")
            if (turn == generation && event.has("delta")) delta(event.optString("delta"))
        }) { outcome ->
            if (turn != generation) return@chat
            request = null
            val error = outcome.optString("error")
            val result = outcome.optJSONObject("result")
            when {
                error.isNotEmpty() -> finish(errorMessage(error, outcome.optInt("retry_after")), false)
                result == null -> finish("Кортекс не получил ответ. Попробуйте ещё раз.", false)
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
        history.addLast(text.take(1600) to answer.take(1200))
        if (history.size > 3) history.removeFirst()
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
