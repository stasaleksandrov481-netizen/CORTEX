package app.cortex.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Executes an ordered plan on the Android main thread, independently of the visible window. */
internal class CortexActionSequence(
    private val commands: List<JSONObject>,
    private val active: () -> Boolean,
    private val execute: (JSONObject, Int, Int, (JSONObject) -> Unit) -> Unit,
    private val finished: (Result) -> Unit
) {
    private val outcomes = mutableListOf<JSONObject>()
    private val reports = mutableListOf<String>()
    private var allConfirmed = true
    private var leftCortex = false

    fun start() = step(0)

    private fun step(index: Int) {
        if (!active()) return
        if (index >= commands.size) {
            complete(0, false)
            return
        }
        val command = commands[index]
        val kind = command.optString("kind")
        var returned = false
        execute(command, index + 1, commands.size) { result ->
            if (!active() || returned) return@execute
            returned = true
            val status = result.optString("status")
            outcomes += JSONObject().put("action", command).put("result", result)
            reports += CortexPhoneResult.describe(command, result)
            leftCortex = leftCortex || status in setOf("opened", "panel") ||
                kind == "lock_screen" && status in setOf("done", "requested") ||
                result.optBoolean("external")
            val confirmed = status in setOf("done", "already_background") || status == "opened" && result.optBoolean("verified")
            allConfirmed = allConfirmed && confirmed
            val handoff = status == "panel" || kind == "lock_screen" && status in setOf("done", "requested")
            if (handoff || status == "cancelled") {
                complete(commands.size - index - 1, true)
            } else step(index + 1)
        }
    }

    private fun complete(remaining: Int, blocked: Boolean) {
        val report = reports.toMutableList()
        if (remaining > 0) report += "Осталось шагов: $remaining. Их можно запустить отдельной командой."
        finished(Result(report.joinToString("\n"), allConfirmed && remaining == 0, leftCortex, JSONArray(outcomes), blocked))
    }

    data class Result(val text: String, val ok: Boolean, val external: Boolean, val observations: JSONArray, val blocked: Boolean)

    companion object {
        const val MAX_STEPS = 8
    }
}
