package app.cortex.assistant

internal class CortexWakeTurn(
    private val listening: () -> Unit,
    private val finished: (String) -> Unit,
    private val closed: () -> Unit
) {
    private var preview = false
    private var confirmedWord = false
    private var armedUntil = 0L
    private var submittedAt = Long.MIN_VALUE / 2
    private var submitted = ""

    fun partial(hypotheses: List<String>) {
        val rest = hypotheses.take(2).firstNotNullOfOrNull(CortexHotword::command) ?: return
        if (rest.isBlank()) confirmedWord = true
        show()
    }

    fun result(hypotheses: List<String>, now: Long) {
        expire(now)
        val command = hypotheses.firstNotNullOfOrNull(CortexHotword::command)
        when {
            command != null && command.isNotBlank() -> submit(command, now)
            command != null -> arm(now)
            armedUntil > now && hypotheses.isNotEmpty() -> submit(hypotheses.first(), now)
            confirmedWord && hypotheses.isEmpty() -> arm(now)
            armedUntil == 0L -> reset()
        }
    }

    fun expire(now: Long) {
        if (armedUntil > 0 && now >= armedUntil) reset()
    }

    fun reset() {
        if (preview || armedUntil > 0) closed()
        preview = false
        confirmedWord = false
        armedUntil = 0L
    }

    fun timeLeft(now: Long) = (armedUntil - now).coerceAtLeast(0)

    private fun show() {
        if (!preview) listening()
        preview = true
    }

    private fun arm(now: Long) {
        armedUntil = now + 8_000L
        confirmedWord = false
        show()
    }

    private fun submit(command: String, now: Long) {
        val text = command.trim().take(600)
        if (text == submitted && now - submittedAt < 1_800L) {
            reset()
            return
        }
        armedUntil = 0
        preview = false
        confirmedWord = false
        submitted = text
        submittedAt = now
        finished(text)
    }
}
