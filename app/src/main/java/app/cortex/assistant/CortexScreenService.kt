package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    override fun onInterrupt() { CortexActionState.stop() }
    override fun onDestroy() {
        if (connected === this) {
            connected = null
            CortexActionState.stop()
        }
        super.onDestroy()
    }

    internal fun activePackage(): String? =
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) null
        else rootInActiveWindow?.let { root ->
            try { root.packageName?.toString() } finally { root.recycle() }
        }

    internal fun inspect(expected: String): ScreenFrame? = inWindow(expected) { root ->
        val elements = mutableListOf<ScreenElement>()
        val observations = linkedSetOf<String>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
            if (depth > 18 || visited++ >= 380 || elements.size >= 55 || sensitive(node)) return
            if (node.packageName?.toString() != expected) return
            if (node.isVisibleToUser) {
                val editable = node.isEditable && node.supports(AccessibilityNodeInfo.ACTION_SET_TEXT)
                val click = node.isEnabled && node.isClickable && !editable
                val scroll = node.isEnabled && node.isScrollable
                val label = labelFor(node)
                val bounds = Rect().apply { node.getBoundsInScreen(this) }
                if (!bounds.isEmpty && (click || editable || scroll)) {
                    elements += ScreenElement(
                        (elements.size + 1).toString(), path, label, node.viewIdResourceName.orEmpty(),
                        node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        bounds, click, editable, scroll
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
        ScreenFrame(expected, root.windowId, elements, observations.toList())
    }

    internal fun apply(frame: ScreenFrame, action: String, element: ScreenElement?, value: String): Boolean {
        if (inspect(frame.pkg) != frame) return false
        if (action == "back") return performGlobalAction(GLOBAL_ACTION_BACK)
        if (element == null) return false
        return inWindow(frame.pkg) { root ->
            if (root.windowId != frame.window) return@inWindow false
            onNode(root, element.path) { node ->
                if (!node.isVisibleToUser || !node.isEnabled || sensitive(node)) return@onNode false
                val current = Rect().apply { node.getBoundsInScreen(this) }
                if (current != element.bounds || node.viewIdResourceName.orEmpty() != element.viewId) return@onNode false
                val label = labelFor(node)
                if (label != element.label) return@onNode false
                when (action) {
                    "tap" -> element.click && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "type" -> element.editable && node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    })
                    "scroll_down" -> element.scroll && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "scroll_up" -> element.scroll && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
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
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() == expected) block(root) else null
        } finally { root.recycle() }
    }

    private fun onNode(root: AccessibilityNodeInfo, path: List<Int>, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var node = root
        try {
            for (index in path) {
                val child = node.getChild(index) ?: return false
                if (node !== root) node.recycle()
                node = child
            }
            return visit(node)
        } finally {
            if (node !== root) node.recycle()
        }
    }

    private fun AccessibilityNodeInfo.supports(action: Int) = actionList.any { it.id == action }

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
        private val STATE_TEXT = Regex("""(?iu)ошиб|сбой|не удалось|не подключ|проверьте|не доступ|недоступ|загрузка|найдено|отправлено|установлено|error|failed|unavailable|try again|loading|no results|sent|installed|net::""")
        private val SYSTEM = Regex("""(?:android|com\.android\.(?:settings|systemui|permissioncontroller|packageinstaller|launcher\d*)|com\.google\.android\.(?:permissioncontroller|packageinstaller|gms|gsf|apps\.nexuslauncher)|com\.sec\.android\.app\.launcher|com\.android\.vending)""")

        fun supportsPackage(pkg: String) = pkg.isNotBlank() && !pkg.startsWith("app.cortex.assistant") &&
            !SYSTEM.matches(pkg.lowercase(Locale.ROOT))
    }
}

internal data class ScreenFrame(val pkg: String, val window: Int, val elements: List<ScreenElement>, val observations: List<String>) {
    fun modelData() = JSONObject().put("элементы", JSONArray(elements.map { it.modelData() }))
        .put("сообщения", JSONArray(observations))
}

internal data class ScreenElement(
    val id: String, val path: List<Int>, val label: String, val viewId: String, val role: String, val bounds: Rect,
    val click: Boolean, val editable: Boolean, val scroll: Boolean
) {
    fun modelData() = JSONObject().put("id", id).put("подпись", label).put("роль", role)
        .put("действия", JSONArray(buildList {
            if (click) add("tap")
            if (editable) add("type")
            if (scroll) addAll(listOf("scroll_down", "scroll_up"))
        }))
}
