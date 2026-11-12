package app.cortex.assistant

import java.text.Normalizer

internal object CortexHotword {
    private val address = Regex(
        """^[\s«"„]*(?:(?:эй|окей|ок|привет)[\s,.:;!?—–-]+)?(?:кортекс|кортэкс|кортексу|кортекст|картекс|кортикс|cortex)(?=$|[\s,.:;!?«»"—–-])""",
        RegexOption.IGNORE_CASE
    )

    fun command(text: String): String? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val prefix = address.find(normalized) ?: return null
        return normalized.substring(prefix.range.last + 1).trimStart {
            it.isWhitespace() || it in ",.:;!?«»\"—–-"
        }.trim()
    }
}
