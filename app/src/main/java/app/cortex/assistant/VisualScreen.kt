package app.cortex.assistant

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

internal enum class VisualScrollAxis { HORIZONTAL, VERTICAL }

internal data class VisualNode(
    val id: String,
    val path: List<Int>,
    val label: String,
    val resource: String,
    val role: String,
    val bounds: Rect,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val ocr: Boolean = false,
    val enabled: Boolean = true,
    val description: String = "",
    val state: String = "",
    val scrollAxis: VisualScrollAxis? = null
)

internal data class VisualFrame(
    val pkg: String,
    val window: Int,
    val bounds: Rect,
    val nodes: List<VisualNode>,
    val secure: Boolean = false,
    val hasPrivateFields: Boolean = false,
    val revision: Long = 0L,
    val scrollRevision: Long = 0L,
    val scrollAxis: VisualScrollAxis? = null
) {
    val tree get() = nodes.filterNot(VisualNode::ocr)

    fun target(label: String, accepts: (VisualNode) -> Boolean = { true }): VisualNode? {
        val byId = nodes.firstOrNull { "#" + it.id == label && accepts(it) && it.enabled }
        if (byId != null) return byId
        val byLabel = nodes.filter { it.enabled && accepts(it) }.map { node ->
            node to maxOf(VisualLabels.score(node.label, label), VisualLabels.score(node.description, label),
                VisualLabels.score(node.resource.substringAfterLast('/').replace('_', ' '), label) - 12)
        }.filter { it.second >= 60 }.sortedByDescending { it.second }
        val best = byLabel.firstOrNull() ?: return null
        return best.first.takeUnless { first ->
            byLabel.drop(1).any { (other, score) -> score >= best.second - 5 &&
                !Rect.intersects(first.bounds, other.bounds) && !(first.ocr.not() && other.ocr) }
        }
    }

    fun has(label: String) = nodes.any { VisualLabels.score(it.label, label) >= 82 ||
        VisualLabels.score(it.description, label) >= 82 }

    fun structure(): Int = tree.map { node ->
        listOf(node.path, node.label, node.role, node.resource, node.bounds, node.checked,
            node.focused, node.selected, node.state, node.enabled, node.editable, node.clickable, node.scrollable)
    }.hashCode()

    fun modelData(): JSONObject = JSONObject().put("package", pkg).put("elements", JSONArray(nodes.filter {
        it.label.isNotBlank() || it.clickable || it.editable || it.scrollable
    }.take(125).map { node ->
        JSONObject().put("id", "#" + node.id).put("label", VisualLabels.forModel(node.label))
            .put("role", node.role).apply {
                if (node.description.isNotBlank()) put("description", VisualLabels.forModel(node.description))
                if (node.resource.isNotBlank()) put("resource", node.resource.substringAfterLast('/'))
                if (node.checked != null) put("checked", node.checked)
                if (node.state.isNotBlank()) put("state", VisualLabels.forModel(node.state))
                if (node.focused) put("focused", true)
                if (node.selected) put("selected", true)
                if (!node.enabled) put("disabled", true)
                if (node.ocr) put("ocr", true)
            }.put("actions", JSONArray(buildList {
                if (node.clickable || node.ocr) add("tap")
                if (node.editable) add("type")
                if (node.scrollable) add("scroll")
            }))
    }))
}

internal enum class VisualRisk { NORMAL, CONFIRM, MANUAL }

internal object VisualSafety {
    private val irreversible = Regex("""(?<![\p{L}\p{N}_])(?:отправ\p{L}*|удал\p{L}*|стереть|сброс\p{L}*|куп\p{L}*|оплат\p{L}*|заказ\p{L}*|подписа\p{L}*|опублик\p{L}*|поделить\p{L}*|установ\p{L}*|разрешить|заблокир\p{L}*|перезап\p{L}*|перезагруз\p{L}*|перевод\p{L}*|перевести|заверш\p{L}*|подтверд\p{L}*|сдать|сфотографир\p{L}*|сделать снимок|send|delete|remove|erase|reset|buy|purchase|pay|order|subscribe|publish|share|install|uninstall|allow|block|overwrite|restart|transfer|finish|submit|confirm|shutter|take photo)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val outbound = Regex("""(?<![\p{L}\p{N}_])(?:отправ\p{L}*|напиш\p{L}*|сообщен\p{L}*|переписк\p{L}*|оплат\p{L}*|перевод\p{L}*|поделить\p{L}*|опублик\p{L}*|удал\p{L}*|куп\p{L}*|message|send|share|post|pay|transfer|delete|buy)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val safeControl = Regex("""(?<![\p{L}\p{N}_])(?:поиск|search|назад|back|найти|find|далее|next|отмена|cancel|список|list|открыть|open|дисплей|display)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val submit = Regex("""(?<![\p{L}\p{N}_])(?:заверш\p{L}*|отправ\p{L}*|сдать|проверить|готово|ответить|finish|submit|send|check|done|answer)(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
    private val school = Regex("""(?:учи[. ]?ру|uchi\.ru|(?:школьн\p{L}*|оцен\p{L}*|контрольн\p{L}*|домашн\p{L}*)\s+(?:задани\p{L}*|работ\p{L}*|тест\p{L}*)|homework|graded assignment)""", RegexOption.IGNORE_CASE)

    fun risk(request: String, step: VisualStep, label: String, resource: String, role: String): VisualRisk {
        val control = VisualLabels.normalize("$label $resource")
        if (step.action !in setOf(VisualAction.TAP, VisualAction.SUBMIT, VisualAction.SET, VisualAction.LOCK)) return VisualRisk.NORMAL
        if (school.containsMatchIn(request) && (step.action == VisualAction.SUBMIT || submit.containsMatchIn(control))) {
            return VisualRisk.MANUAL
        }
        if (step.confirm || irreversible.containsMatchIn(control)) return VisualRisk.CONFIRM
        if (step.action == VisualAction.SUBMIT && outbound.containsMatchIn(request)) return VisualRisk.CONFIRM
        if (step.action == VisualAction.TAP && outbound.containsMatchIn(request) &&
            !safeControl.containsMatchIn(control) && (label.isBlank() || role in setOf("ImageButton", "ImageView", "OCR"))) {
            return VisualRisk.CONFIRM
        }
        return VisualRisk.NORMAL
    }
}
