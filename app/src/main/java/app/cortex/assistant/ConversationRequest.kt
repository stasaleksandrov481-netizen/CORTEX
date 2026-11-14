package app.cortex.assistant

internal data class ConversationRequest(
    val app: String,
    val recipient: String,
    val message: String,
    val send: Boolean
) {
    val question: String get() = when {
        recipient.isBlank() -> "Как точно называется переписка или контакт?"
        message.isBlank() -> "Какой текст написать?"
        else -> ""
    }

    fun withAnswer(text: String): ConversationRequest = if (recipient.isBlank()) {
        copy(recipient = recipientName(text))
    } else {
        val mode = draft.find(text.replace(quoted, { " ".repeat(it.value.length) }))
        copy(message = if (mode == null) unquote(text.trim()).take(2_000) else
            unquote(text.removeRange(mode.range).trim().trimEnd(',')).take(2_000),
            send = send && mode == null)
    }

    companion object {
        private val appName = Regex("""(?:^|[\s,])(?:открой|запусти|open)\s+(?:приложение\s+)?(.+?)(?=\s*(?:[,;]|\s+(?:и\s+)?(?:зайди|перейди|найди|открой\s+(?:чат|переписку)|напиши|отправь)\b)|$)""", RegexOption.IGNORE_CASE)
        private val quoted = Regex("""«([^»]+)»|„([^“]+)“|"([^"]+)"""")
        private val appIn = Regex("""\b(?:в|in)\s+([\p{L}\p{N}._ -]+?)(?=\s*[,;:]|\s+(?:и\s+)?(?:найди|зайди|напиши|отправь|контакту|пользователю)\b|$)""", RegexOption.IGNORE_CASE)
        private val chatName = Regex("""(?:чат|переписк\p{L}*|диалог|личку|chat)\s+(?:с\s+|with\s+)?(.+?)(?=\s*(?:[,;:]|\s+(?:и\s+)?(?:напиши|отправь|введи|набери|подготовь)\b)|$)""", RegexOption.IGNORE_CASE)
        private val explanatory = Regex("""^(?:как|почему|объясни|подскажи как|how|why|explain)\b""")
        private val to = Regex("""\b(?:напиши|отправь)\s+(?:(?:сообщение|текст)\s+)?(?:контакту\s+|пользователю\s+)?(.+?)(?=\s*(?:\b(?:в|in)\s+|[:«"]))""", RegexOption.IGNORE_CASE)
        private val composing = Regex("""\b(?:напиши|отправь|набери|подготовь|введи|send|write)\b""", RegexOption.IGNORE_CASE)
        private val messageMarker = Regex("""\b(?:напиши|отправь|набери|подготовь|введи|send|write)\s*(?:сообщение|текст)?\s*[:—]?\s*(.*)""", RegexOption.IGNORE_CASE)
        private val draft = Regex("""\b(?:черновик|не\s+отправляй|без\s+отправки|draft)\b""", RegexOption.IGNORE_CASE)
        private val genericRecipient = Regex("""^(?:друг\p{L}*|подруг\p{L}*|контакт\p{L}*|человек\p{L}*|собеседник\p{L}*|кому нибудь|с кем нибудь|friend|contact)$""")

        fun parse(text: String, resolve: (String) -> Boolean): ConversationRequest? {
            val goal = VisualRequest.goal(text)
            val command = CortexHotword.command(goal) ?: goal
            val unquoted = command.replace(quoted) { " ".repeat(it.value.length) }
            if (explanatory.containsMatchIn(VisualLabels.normalize(unquoted)) || !composing.containsMatchIn(unquoted)) return null
            val chat = chatName.find(command)
            val hasMessage = Regex("""\b(?:сообщение|черновик|напиши|отправь|message)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(command)
            if (chat == null && !hasMessage) return null
            val app = sequenceOf(appName.findAll(command), appIn.findAll(command)).flatten()
                .filter { match -> unquoted.substring(match.range.first).take(4).any { !it.isWhitespace() } }
                .map { unquote(it.groupValues[1].trim().trimEnd('.', '!', '?')) }
                .firstOrNull { it.length in 2..70 && resolve(it) } ?: return null
            val recipient = recipientName(chat?.groupValues?.get(1) ?: to.find(command)?.groupValues?.get(1).orEmpty())
            val tail = messageMarker.findAll(command).lastOrNull()?.groupValues?.get(1).orEmpty()
            val quote = quoted.find(tail)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            val tailWithoutMode = tail.replace(draft, "").trim().trimEnd(',')
            val inline = tailWithoutMode.substringAfter(':', "").trim().takeIf(String::isNotBlank)
            val explicit = if (chat != null) tailWithoutMode else ""
            val message = (quote ?: inline?.let(::unquote) ?: unquote(explicit)).trim().take(2_000)
            val draftOnly = draft.containsMatchIn(unquoted)
            return ConversationRequest(app, recipient, message, !draftOnly)
        }

        private fun recipientName(value: String): String {
            val name = unquote(value.trim().trimEnd('.', ',', ';', '!', '?'))
            val clean = VisualLabels.normalize(name)
            return name.takeIf { it.length in 1..90 && clean.isNotBlank() && !genericRecipient.matches(clean) }.orEmpty()
        }

        private fun unquote(value: String) =
            value.removeSurrounding("«", "»").removeSurrounding("„", "“").removeSurrounding("\"")
    }
}
