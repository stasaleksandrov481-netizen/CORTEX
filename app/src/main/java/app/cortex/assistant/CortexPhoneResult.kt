package app.cortex.assistant

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object CortexPhoneResult {
    fun describe(command: JSONObject, result: JSONObject): String {
        val kind = command.optString("kind")
        return when (result.optString("status")) {
            "done" -> when (kind) {
                "volume" -> "Громкость мультимедиа — " + result.optInt("level") + "%."
                "torch" -> if (result.optBoolean("enabled")) "Фонарик включён." else "Фонарик выключен."
                "lock_screen" -> "Android подтвердил блокировку экрана."
                "read_device" -> when (command.optString("target")) {
                    "time" -> "Сейчас " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "."
                    "battery" -> result.optInt("level", -1).takeIf { it in 0..100 }?.let {
                        "Заряд — $it%." + if (result.optBoolean("charging")) " Телефон заряжается." else ""
                    } ?: "Android не сообщил уровень заряда."
                    "wifi" -> if (result.optBoolean("enabled")) "Wi-Fi включён." else "Wi-Fi выключен."
                    else -> result.optString("value", "Состояние получено от Android.")
                }
                "brightness" -> "Яркость экрана — " + result.optInt("level") + "%."
                "battery" -> "Заряд — " + result.optInt("level") + "%."
                "app_list" -> result.optJSONArray("names")?.let { names ->
                    (0 until minOf(names.length(), 18)).joinToString(", ") { names.optString(it) }
                }.orEmpty().ifBlank { "Приложения не найдены." }
                "copy_text" -> "Текст скопирован."
                else -> "Android подтвердил действие."
            }
            "opened" -> when (kind) {
                "app" -> if (result.optBoolean("verified")) "Открыто «" + result.optString("name") + "»."
                    else "Android получил запрос открыть «" + result.optString("name") + "»."
                "home" -> "Android получил команду перейти на рабочий стол."
                "recents" -> "Android получил команду показать список окон."
                "settings" -> if (command.optString("target") == "vpn") "Переключатель VPN находится в открываемых настройках Android."
                    else "Android получил команду открыть настройки."
                else -> "Android получил команду открытия."
            }
            "panel" -> if (kind == "play_store") "Поиск передан Google Play. Выберите приложение и подтвердите установку на его странице."
                else "Открываю системную панель Wi-Fi. Переключатель находится на её экране."
            "requested" -> "Android получил команду. Подтверждения результата пока нет."
            "needs_lock" -> "Разрешите блокировку экрана или включите управление экраном в настройках CORTEX."
            "not_installed" -> "Приложение не найдено на телефоне."
            "unsupported" -> "Действие недоступно с текущими возможностями Android."
            else -> "Android не подтвердил выполнение команды."
        }
    }

}
