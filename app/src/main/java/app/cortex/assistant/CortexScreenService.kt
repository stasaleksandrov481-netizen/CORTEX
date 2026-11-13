package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Reads the foreground tree only on demand, during a user-started task. */
class CortexScreenService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (connected === this) {
            connected = null
        }
        super.onDestroy()
    }

    internal fun activePackage(): String? =
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) null
        else applicationRoot()?.let { root ->
            try { root.packageName?.toString() } finally { root.recycle() }
        }

    internal fun inspect(expected: String): ScreenFrame? = inWindow(expected) { root ->
        val elements = mutableListOf<ScreenElement>()
        val observations = linkedSetOf<String>()
        val headings = linkedSetOf<String>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
            if (depth > 18 || visited++ >= 380 || elements.size >= 55 || sensitive(node)) return
            if (node.packageName?.toString() != expected) return
            if (node.isVisibleToUser) {
                val editable = node.isEditable && node.supports(AccessibilityNodeInfo.ACTION_SET_TEXT)
                val click = node.isEnabled && node.isClickable && !editable
                val scrollUp = node.isEnabled && node.supports(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                val scrollDown = node.isEnabled && node.supports(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                val submit = editable && node.isFocused && (
                    Build.VERSION.SDK_INT >= 30 && node.supports(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ||
                        searchEditorAction(expected) != null)
                val label = labelFor(node)
                val bounds = Rect().apply { node.getBoundsInScreen(this) }
                val selected = node.isSelected || node.isChecked
                val resource = node.viewIdResourceName.orEmpty()
                if (!editable && label.isNotBlank() && headings.size < 8 &&
                    (Build.VERSION.SDK_INT >= 28 && node.isHeading || TITLE_ID.containsMatchIn(resource.substringAfterLast('/')))) {
                    headings += label
                }
                if (!bounds.isEmpty && (click || editable || scrollUp || scrollDown || selected && label.isNotBlank())) {
                    elements += ScreenElement(
                        (elements.size + 1).toString(), path, label, resource,
                        node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        bounds, click, editable, scrollUp, scrollDown, submit, node.isFocused, selected
                    )
                } else if (!node.isEditable && observations.size < 8 && STATE_TEXT.containsMatchIn(label)) {
                    observations += label
                }
            }
            for (index in 0 until minOf(node.childCount, 80)) {
                if (visited >= 380 || elements.size >= 55) break
                node.getChild(index)?.let { child ->
                    try { visit(child, path + index, depth + 1) } finally { child.recycle() }
                }
            }
        }
        visit(root, emptyList(), 0)
        ScreenFrame(expected, root.windowId, elements, observations.toList(), headings.toList())
    }

    internal fun apply(frame: ScreenFrame, action: String, element: ScreenElement?, value: String): Boolean {
        return inWindow(frame.pkg) { root ->
            if (root.windowId != frame.window || sensitive(root)) return@inWindow false
            if (action == "back") return@inWindow performGlobalAction(GLOBAL_ACTION_BACK)
            if (element == null) return@inWindow false
            onNode(root, element.path) { node ->
                if (!node.isVisibleToUser || !node.isEnabled || sensitive(node)) return@onNode false
                val current = Rect().apply { node.getBoundsInScreen(this) }
                if (!Rect.intersects(current, element.bounds) || node.viewIdResourceName.orEmpty() != element.viewId ||
                    node.className?.toString()?.substringAfterLast('.').orEmpty() != element.role) return@onNode false
                val label = labelFor(node)
                if (label != element.label) return@onNode false
                when (action) {
                    "tap" -> element.click && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "focus" -> element.editable && node.isEditable &&
                        (node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    "type" -> element.editable && node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    })
                    "submit" -> element.submit && node.isEditable && node.isFocused && submitEditor(node, frame.pkg)
                    "scroll_down" -> element.scrollDown && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "scroll_up" -> element.scrollUp && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    else -> false
                }
            }
        } == true
    }

    internal fun fieldMatches(frame: ScreenFrame, element: ScreenElement, value: String): Boolean =
        inWindow(frame.pkg) { root ->
            root.windowId == frame.window && onNode(root, element.path) { node ->
                node.isEditable && !sensitive(node) && node.viewIdResourceName.orEmpty() == element.viewId &&
                    node.text?.toString() == value
            }
        } == true

    private fun <T> inWindow(expected: String, block: (AccessibilityNodeInfo) -> T): T? {
        if (!supportsPackage(expected) || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val root = applicationRoot() ?: return null
        return try {
            if (root.packageName?.toString() == expected) block(root) else null
        } finally { root.recycle() }
    }

    private fun applicationRoot(): AccessibilityNodeInfo? {
        if (connected !== this) return null
        val root = rootInActiveWindow ?: return null
        val interactive = windows
        try {
            if (interactive.none { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return root
            root.recycle()
            return interactive.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
        } finally {
            interactive.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    private fun onNode(root: AccessibilityNodeInfo, path: List<Int>, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var node = root
        try {
            for (index in path) {
                val child = node.getChild(index) ?: return false
                if (node !== root) node.recycle()
                node = child
                if (sensitive(node)) return false
            }
            return visit(node)
        } finally {
            if (node !== root) node.recycle()
        }
    }

    private fun AccessibilityNodeInfo.supports(action: Int) = actionList.any { it.id == action }

    private fun submitEditor(node: AccessibilityNodeInfo, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && node.supports(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        if (Build.VERSION.SDK_INT < 33) return false
        val action = searchEditorAction(pkg) ?: return false
        val editor = inputMethod?.currentInputConnection ?: return false
        editor.performEditorAction(action)
        return true
    }

    private fun searchEditorAction(pkg: String): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        val editor = inputMethod ?: return null
        val info = editor.currentInputEditorInfo?.takeIf { editor.currentInputStarted && it.packageName == pkg } ?: return null
        return (info.imeOptions and EditorInfo.IME_MASK_ACTION).takeIf {
            it == EditorInfo.IME_ACTION_SEARCH || it == EditorInfo.IME_ACTION_GO
        }
    }

    private fun sensitive(node: AccessibilityNodeInfo) =
        node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            SENSITIVE_ID.containsMatchIn(node.viewIdResourceName.orEmpty().substringAfterLast('/'))

    private fun labelFor(node: AccessibilityNodeInfo): String {
        if (node.isEditable) return clean(node.hintText ?: node.contentDescription)
        val direct = clean(node.contentDescription ?: node.text)
        if (direct.isNotBlank() || !node.isClickable) return direct
        for (index in 0 until minOf(node.childCount, 6)) {
            val child = node.getChild(index) ?: continue
            try {
                if (!child.isEditable && child.isVisibleToUser && !sensitive(child)) {
                    val label = clean(child.contentDescription ?: child.text)
                    if (label.isNotBlank()) return label
                }
            } finally { child.recycle() }
        }
        return direct
    }

    private fun clean(value: CharSequence?): String = value?.toString().orEmpty()
        .replace(CONTROLS, " ").trim().take(150)
        .replace(EMAIL, "[почта]").replace(LONG_NUMBER, "[номер]").replace(URL, "[адрес]")

    internal companion object {
        var connected: CortexScreenService? = null
            private set
        private val CONTROLS = Regex("[\\p{Cc}\\p{Cf}\\s]+")
        private val EMAIL = Regex("""[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}""")
        private val LONG_NUMBER = Regex("""(?<!\d)(?:\d[ ()+-]?){6,}\d?""")
        private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
        private val SENSITIVE_ID = Regex("""(?i)password|passcode|credit_card|card_number|one_time|otp_input|pin_code|private_key""")
        private val TITLE_ID = Regex("""(?i)(?:^|_)(?:toolbar_title|header_title|action_bar_title|page_title|screen_title|conversation_title|title)(?:$|_)""")
        private val STATE_TEXT = Regex("""(?iu)ошиб|сбой|не удалось|не подключ|проверьте|не доступ|недоступ|загрузка|найдено|отправлено|установлено|error|failed|unavailable|try again|loading|no results|sent|installed|net::""")
        private val SYSTEM = Regex("""(?:android|com\.android\.(?:settings|systemui|permissioncontroller|packageinstaller|launcher\d*)|com\.google\.android\.(?:permissioncontroller|packageinstaller|gms|gsf|apps\.nexuslauncher)|com\.sec\.android\.app\.launcher|com\.android\.vending)""")

        fun supportsPackage(pkg: String) = pkg.isNotBlank() && !pkg.startsWith("app.cortex.assistant") &&
            !SYSTEM.matches(pkg.lowercase(Locale.ROOT))
    }
}

internal data class ScreenFrame(
    val pkg: String, val window: Int, val elements: List<ScreenElement>, val observations: List<String>, val headings: List<String>
) {
    fun modelData() = JSONObject().put("элементы", JSONArray(elements.map { it.modelData() }))
        .put("сообщения", JSONArray(observations)).put("заголовки", JSONArray(headings))
}

internal data class ScreenElement(
    val id: String, val path: List<Int>, val label: String, val viewId: String, val role: String, val bounds: Rect,
    val click: Boolean, val editable: Boolean, val scrollUp: Boolean, val scrollDown: Boolean,
    val submit: Boolean, val focused: Boolean, val selected: Boolean
) {
    fun modelData() = JSONObject().put("id", id).put("подпись", label).put("роль", role)
        .apply {
            if (focused) put("фокус", true)
            if (selected) put("выбран", true)
        }.put("действия", JSONArray(buildList {
            if (click) add("tap")
            if (editable) addAll(listOf("focus", "type"))
            if (submit && focused) add("submit")
            if (scrollUp) add("scroll_up")
            if (scrollDown) add("scroll_down")
        }))
}
