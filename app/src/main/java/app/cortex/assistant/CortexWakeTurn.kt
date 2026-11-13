package app.cortex.assistant

internal class CortexWakeTurn(
    private val listening: () -> Unit,
    private val finished: (String) -> Unit,
    private val closed: () -> Unit
) {
    private var preview = false
    private var confirmedWord = false
    private var armedUntil = 0L
    private var segmentUntil = 0L
    private var segments = ""
    private var submittedAt = Long.MIN_VALUE / 2
    private var submitted = ""

    fun partial(hypotheses: List<String>) {
        val rest = hypotheses.take(2).firstNotNullOfOrNull(CortexHotword::command) ?: return
        if (rest.isBlank()) confirmedWord = true
        show()
    }

    fun result(hypotheses: List<String>, now: Long, segmented: Boolean = false) {
        expire(now)
        val command = hypotheses.firstNotNullOfOrNull(CortexHotword::command)
        when {
            segmentUntil > now -> (command ?: hypotheses.firstOrNull())?.takeIf(String::isNotBlank)?.let { collect(it, now) }
            command != null && command.isNotBlank() -> if (segmented) collect(command, now) else submit(command, now)
            command != null -> arm(now)
            armedUntil > now && hypotheses.isNotEmpty() -> if (segmented) collect(hypotheses.first(), now) else submit(hypotheses.first(), now)
            confirmedWord && hypotheses.isEmpty() -> arm(now)
            armedUntil == 0L -> reset()
        }
    }

    fun expire(now: Long) {
        if (segmentUntil > 0 && now >= segmentUntil) submit(segments, now)
        else if (armedUntil > 0 && now >= armedUntil) reset()
    }

    fun reset() {
        if (preview || armedUntil > 0) closed()
        preview = false
        confirmedWord = false
        armedUntil = 0L
        segmentUntil = 0L
        segments = ""
    }

    fun timeLeft(now: Long) = (maxOf(armedUntil, segmentUntil) - now).coerceAtLeast(0)

    private fun show() {
        if (!preview) listening()
        preview = true
    }

    private fun arm(now: Long) {
        armedUntil = now + 8_000L
        confirmedWord = false
        show()
    }

    private fun collect(command: String, now: Long) {
        val fragment = command.trim()
        segments = when {
            segments.isEmpty() || fragment.startsWith(segments, ignoreCase = true) -> fragment
            segments.startsWith(fragment, ignoreCase = true) -> segments
            else -> "$segments $fragment"
        }.take(2400)
        armedUntil = 0L
        segmentUntil = now + 1_350L
        show()
    }

    private fun submit(command: String, now: Long) {
        val text = command.trim().take(2400)
        if (text == submitted && now - submittedAt < 1_800L) {
            reset()
            return
        }
        armedUntil = 0
        segmentUntil = 0
        segments = ""
        preview = false
        confirmedWord = false
        submitted = text
        submittedAt = now
        finished(text)
    }
}
