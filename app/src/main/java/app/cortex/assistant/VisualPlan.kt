package app.cortex.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class VisualAction {
    HOME, BACK, QUICK_SETTINGS, OPEN, TAP, SET, TYPE, SUBMIT, SCROLL, SWIPE, READ, EXPECT, LOCK
}

internal data class VisualStep(
    val action: VisualAction,
    val target: String = "",
    val value: String = "",
    val expect: String = "",
    val confirm: Boolean = false
)

internal data class VisualPlan(val steps: List<VisualStep>, val question: String, val answer: String, val done: Boolean) {
    companion object {
        fun parse(raw: String): VisualPlan? = runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start && end - start < 18_000)
            val json = JSONObject(raw.substring(start, end + 1))
            require(json.keys().asSequence().all { it in setOf("steps", "question", "answer", "done") })
            val question = json.text("question", 1200)
            val answer = json.text("answer", 6000)
            val done = if (json.has("done")) json.get("done") as Boolean else false
            val steps = if (!json.has("steps")) JSONArray() else json.getJSONArray("steps")
            require(steps.length() <= 20)
            val parsed = (0 until steps.length()).map { parseStep(steps.getJSONObject(it)) }
            require(parsed.isNotEmpty() || question.isNotBlank() || answer.isNotBlank() || done)
            require(!done || question.isBlank() && parsed.none { it.action == VisualAction.READ })
            VisualPlan(parsed, question, answer, done)
        }.getOrNull()

        private fun parseStep(json: JSONObject): VisualStep {
            require(json.keys().asSequence().all { it in setOf("action", "target", "value", "expect", "confirm") })
            val action = VisualAction.valueOf(json.getString("action").uppercase(Locale.ROOT))
            val target = json.text("target", 180)
            val value = json.text("value", 2400)
            val expect = json.text("expect", 180)
            val confirm = if (json.has("confirm")) json.getBoolean("confirm") else false
            when (action) {
                VisualAction.OPEN, VisualAction.TAP, VisualAction.EXPECT -> require(target.isNotBlank() && value.isEmpty())
                VisualAction.SET -> require(target.isNotBlank() && value in setOf("on", "off"))
                VisualAction.TYPE -> require(value.isNotBlank())
                VisualAction.SCROLL, VisualAction.SWIPE -> require(value in setOf("up", "down", "left", "right"))
                VisualAction.HOME, VisualAction.BACK, VisualAction.QUICK_SETTINGS, VisualAction.SUBMIT,
                VisualAction.READ, VisualAction.LOCK -> require(target.isEmpty() && value.isEmpty())
            }
            return VisualStep(action, target, value, expect, confirm)
        }

        private fun JSONObject.text(key: String, max: Int): String {
            if (!has(key)) return ""
            val value = get(key) as String
            require(value.length <= max && value.none { it == '\u0000' })
            return if (key == "value") value else value.trim()
        }
    }
}

internal object VisualLabels {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val hidden = Regex("[\\p{Cc}\\p{Cf}]")
    private val email = Regex("""[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}""")
    private val number = Regex("""(?<!\w)\+?\d(?:[ ()-]*\d){5,}(?!\w)""")
    private val urlSecrets = Regex("""(https?://[^\s?#]+)[?#]\S+""", RegexOption.IGNORE_CASE)
    private val bearer = Regex("""(?<![\p{L}\p{N}_])(?:bearer\s+|sk-[a-z0-9_-]*-)[a-z0-9_.-]{12,}""", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(separators, " ").trim()

    fun clean(text: CharSequence?, max: Int = 260): String = text?.toString().orEmpty()
        .replace(hidden, " ").replace(spaces, " ").trim().take(max)

    fun forModel(text: String) = clean(text, 360).replace(email, "[почта]").replace(number, "[номер]")
        .replace(urlSecrets, "$1").replace(bearer, "[ключ]")

    fun score(label: String, target: String): Int {
        val a = normalize(label)
        val b = normalize(target)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (b.length < 3) return 0
        if (" $a ".contains(" $b ")) return 82
        if (a.length >= 4 && " $b ".contains(" $a ")) return 66
        val left = a.split(' ')
        val right = b.split(' ')
        if (right.size <= left.size + 1 && right.all { word ->
                left.any { it == word || (word.length >= 4 && it.length >= 4 && it.take(4) == word.take(4) &&
                    (it.startsWith(word.dropLast(1)) || word.startsWith(it.dropLast(1)))) }
            }) return 60
        return 0
    }
}

internal object VisualRequest {
    private val action = Regex("""(?<![\p{L}\p{N}_])(?:открой|запусти|зайди|перейди|найди|отыщи|покажи|нажми|нажать|пролистай|прокрути|свайпни|введи|вставь|заполни|набери|напиши|отправь|создай|удали|перемести|скачай|установи|измени|поменяй|поставь|выбери|настрой|включи|выключи|отключи|закрой|выйди|заблокируй|разблокируй|исправь|почини|перезапусти|сделай|выполни|прочитай|пройди|запиши|реши|выполнить|открыть|включить|выключить|open|launch|tap|scroll|swipe|type|send|create|delete|install|close|enable|disable|turn|navigate|select|read|fill|find)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val explain = Regex("""^(?:как|зачем|почему|можно ли|что (?:такое|будет|умеешь)|объясни|расскажи|подскажи,? как|how|why|explain)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val device = Regex("""(?:(?<![\p{L}\p{N}_])(?:телефон|экран|android|приложени|браузер|настройк|вкладк|фонарик|проводник|кнопк|ярлык|папк|камер|файл|фото|частот[ау]|герцовк|шторк|рабочий стол|тест на|сайт|переписк|личк|контакт|сообщени|desktop|settings|app|browser|screen|folder|flashlight|http|https)|\w+\.(?:ru|com|org)(?![\p{L}\p{N}_]))""", RegexOption.IGNORE_CASE)
    private val generic = Regex("""^(?:напиши|сделай|найди|покажи|прочитай|реши|запиши|исправь|выполни|настрой|напиши мне|write|show|read|find)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val resume = Regex("""^(?:(?:давай\s+)?продолж(?:и|ай)(?:\s+(?:задачу|действия))?|возобнови(?:\s+задачу)?|resume(?:\s+task)?)[.!]?$""", RegexOption.IGNORE_CASE)
    private val open = Regex("""^(?:открой|открыть|запусти|запустить|open|launch)\s+(?:приложение\s+|the\s+app\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val toggle = Regex("""^(включи|включить|выключи|выключить|отключи|отключить)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val home = Regex("""^(?:домой|на рабочий стол|выйди на (?:главный экран|рабочий стол)|go home)$""", RegexOption.IGNORE_CASE)
    private val back = Regex("""^(?:назад|вернись назад|go back)$""", RegexOption.IGNORE_CASE)
    private val quickSettings = Regex("""^(?:открой (?:быстрые настройки|шторку)|open quick settings)$""", RegexOption.IGNORE_CASE)
    private val close = Regex("""^(?:закрой|закрыть|сверни|выйди из|close)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val lock = Regex("""^(?:заблокируй(?: телефон| экран)?|(?:выключи|выключить|погаси) экран|lock(?: the)? (?:phone|screen))$""", RegexOption.IGNORE_CASE)
    private val takePhoto = Regex("""^(?:сделай (?:мне )?(?:одно )?(?:фото|снимок)|сфотографируй|сфоткайся|take (?:a )?photo)$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s*(?:[,;\n]+\s*(?:затем\s+|потом\s+|then\s+)?|\s+(?:(?:и |а )?(?:затем|потом|после этого|после чего)|and then|then)\s+|\s+и\s+(?=(?:открой|открыть|закрой|закрыть|выключи(?:ть)?|включи(?:ть)?|заблокируй|нажми|выбери|введи|вставь|прокрути|пролистай|свайпни|вернись)(?![\p{L}\p{N}_])))""", RegexOption.IGNORE_CASE)
    private val separatedVoice = Regex("""\s+(?:и\s+)?(?=(?:открой|открыть|закрой|закрыть|сверни|выключи(?:ть)?|включи(?:ть)?|заблокируй|погаси)(?![\p{L}\p{N}_]))""", RegexOption.IGNORE_CASE)
    private val tap = Regex("""^(?:нажми(?:те)?(?:\s+на)?|выбери(?:те)?|tap|select)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val input = Regex("""^(?:введи|вставь|набери|type|enter)\s+(?:текст\s+)?["«]([\s\S]+?)["»](?:\s+(?:в|in)\s+(.+))?$""", RegexOption.IGNORE_CASE)
    private val scroll = Regex("""^(?:пролистай|прокрути|scroll)\s+(?:экран\s+)?(вверх|вниз|влево|вправо|up|down|left|right)$""", RegexOption.IGNORE_CASE)
    private val swipe = Regex("""^(?:свайпни|свайп|swipe)\s+(?:экран\s+)?(вверх|вниз|влево|вправо|up|down|left|right)$""", RegexOption.IGNORE_CASE)
    private val directions = mapOf("вверх" to "up", "вниз" to "down", "влево" to "left", "вправо" to "right")
    private val quoted = Regex("""(?:"[^"]*"|«[^»]*»)""")

    fun isResume(text: String) = resume.matches(text.trim())
    fun goal(text: String): String = text.replaceFirst(actionPrefix, "").trim()

    private val actionPrefix = Regex("^действие:\\s*", RegexOption.IGNORE_CASE)

    fun isAction(text: String): Boolean {
        val command = text.trim().trimEnd('.', '!', '?')
        if (text.startsWith("действие:", true) || isResume(text) || home.matches(command) ||
            back.matches(command) || swipe.matches(command) || lock.matches(command) || takePhoto.matches(command)) return true
        if (explain.containsMatchIn(text) || !action.containsMatchIn(text)) return false
        return !generic.containsMatchIn(text) || device.containsMatchIn(text)
    }

    fun local(text: String, app: (String) -> Boolean): List<VisualStep>? {
        val command = CortexHotword.command(goal(text))?.takeIf(String::isNotBlank) ?: goal(text)
        val quotedRanges = quoted.findAll(command).map { it.range }.toList()
        val clauses = buildList {
            var start = 0
            for (match in separators.findAll(command)) {
                if (quotedRanges.any { match.range.first in it }) continue
                add(command.substring(start, match.range.first))
                start = match.range.last + 1
            }
            add(command.substring(start))
        }
        val phrases = clauses.flatMap { part ->
            if ('"' in part || '«' in part) listOf(part) else part.split(separatedVoice)
        }
        return phrases.flatMap { part ->
            val clean = part.trim().trimEnd('.', '!', '?')
            when {
                home.matches(clean) -> listOf(VisualStep(VisualAction.HOME))
                back.matches(clean) -> listOf(VisualStep(VisualAction.BACK))
                quickSettings.matches(clean) -> listOf(VisualStep(VisualAction.QUICK_SETTINGS))
                lock.matches(clean) -> listOf(VisualStep(VisualAction.LOCK))
                takePhoto.matches(clean) && app("камера") -> listOf(VisualStep(VisualAction.OPEN, "камера"), VisualStep(VisualAction.READ))
                open.matches(clean) -> {
                    val path = open.matchEntire(clean)!!.groupValues[1].split(Regex("\\s*(?:→|->)\\s*"))
                        .map { it.trim().trim('"', '«', '»') }
                    if (path.any(String::isBlank) || !app(path.first())) return null
                    listOf(VisualStep(VisualAction.OPEN, path.first())) +
                        path.drop(1).map { VisualStep(VisualAction.TAP, it) }
                }
                close.matches(clean) -> {
                    val name = close.matchEntire(clean)!!.groupValues[1]
                    if (!app(name)) return null
                    listOf(VisualStep(VisualAction.HOME, expect = name))
                }
                toggle.matches(clean) -> {
                    val (_, verb, label) = toggle.matchEntire(clean)!!.groupValues
                    if (label.length > 45 || label.split(' ').size > 4) return null
                    listOf(VisualStep(VisualAction.QUICK_SETTINGS),
                        VisualStep(VisualAction.SET, label, if (verb.startsWith("включ", true)) "on" else "off"))
                }
                tap.matches(clean) -> {
                    val label = tap.matchEntire(clean)!!.groupValues[1].trim().trim('"', '«', '»')
                    if (label.length !in 1..100) return null
                    listOf(VisualStep(VisualAction.TAP, label))
                }
                input.matches(clean) -> {
                    val (_, value, field) = input.matchEntire(clean)!!.groupValues
                    listOf(VisualStep(VisualAction.TYPE, field.trim().trim('"', '«', '»'), value))
                }
                scroll.matches(clean) -> {
                    val direction = scroll.matchEntire(clean)!!.groupValues[1].lowercase(Locale.ROOT)
                    listOf(VisualStep(VisualAction.SCROLL, value = directions[direction] ?: direction))
                }
                swipe.matches(clean) -> {
                    val direction = swipe.matchEntire(clean)!!.groupValues[1].lowercase(Locale.ROOT)
                    listOf(VisualStep(VisualAction.SWIPE, value = directions[direction] ?: direction))
                }
                else -> return null
            }
        }.takeIf { it.size in 1..20 }
    }
}
