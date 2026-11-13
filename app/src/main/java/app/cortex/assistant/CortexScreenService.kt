package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The visual task owns screen reads. Accessibility events only mark screen changes. */
class CortexScreenService : AccessibilityService() {
    private var revision = 0L
    private var scrolledWindow = -1
    private var scrollRevision = 0L
    private var touch: VisualTouchOverlay? = null
    private var ocr: VisualOcr? = null
    private var progress: VisualProgressOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scrolledWindow = event.windowId
                scrollRevision = ++revision
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> revision++
        }
    }

    override fun onInterrupt() { CortexActionState.stop() }

    override fun onDestroy() {
        if (connected === this) {
            connected = null
            CortexActionState.stop()
        }
        progress?.close()
        touch?.close()
        super.onDestroy()
    }

    internal fun activePackage(): String? = if (locked()) null else withRoot { it.packageName?.toString() }
    internal fun locked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    internal fun homePackages(): Set<String> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    ).filterNot { it.activityInfo.name.substringAfterLast('.') == "FallbackHome" }
        .mapTo(mutableSetOf()) { it.activityInfo.packageName }

    internal fun frame(): VisualFrame? {
        if (locked()) return null
        return withRoot { root ->
            val pkg = root.packageName?.toString() ?: return@withRoot null
            val frameBounds = windowBounds(root.windowId)
            val nodes = mutableListOf<VisualNode>()
            var visited = 0
            var privateFields = false
            fun visit(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
                if (++visited > 560 || nodes.size >= 220 || depth > 22 || node.packageName?.toString() != pkg) return
                if (sensitive(node)) {
                    privateFields = true
                    return
                }
                if (node.isVisibleToUser) {
                    val raw = Rect().apply { node.getBoundsInScreen(this) }
                    val editable = node.isEditable
                    val text = if (editable) VisualLabels.clean(node.hintText) else VisualLabels.clean(node.text)
                    val description = VisualLabels.clean(node.contentDescription)
                    val scrollable = node.isScrollable || node.supports(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                        node.supports(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    val checkable = if (node.isCheckable) node.isChecked else null
                    if (raw.intersect(frameBounds) && (text.isNotBlank() || description.isNotBlank() || node.isClickable ||
                            editable || scrollable || checkable != null)) {
                        nodes += VisualNode((nodes.size + 1).toString(), path, text.ifBlank { description },
                            node.viewIdResourceName.orEmpty(), node.className?.toString()?.substringAfterLast('.').orEmpty(),
                            raw, node.isClickable, editable, scrollable, node.isFocused, checkable, node.isSelected,
                            enabled = node.isEnabled, description = description,
                            state = if (Build.VERSION.SDK_INT >= 30) VisualLabels.clean(node.stateDescription) else "")
                    }
                }
                for (index in 0 until minOf(node.childCount, 100)) {
                    if (visited >= 560 || nodes.size >= 220) break
                    node.getChild(index)?.let { child ->
                        try { visit(child, path + index, depth + 1) } finally { child.recycle() }
                    }
                }
            }
            val secure = sensitive(root) || MANUAL_PACKAGES.matches(pkg)
            if (!secure) visit(root, emptyList(), 0)
            VisualFrame(pkg, root.windowId, frameBounds, nodes, secure, privateFields, revision,
                if (root.windowId == scrolledWindow) scrollRevision else 0)
        }
    }

    internal suspend fun recognize(frame: VisualFrame): OcrResult {
        if (frame.secure || frame.hasPrivateFields || Build.VERSION.SDK_INT < 30) return OcrResult()
        val recognizer = ocr ?: VisualOcr(this).also { ocr = it }
        return recognizer.read(frame, displayBounds())
    }

    internal fun stopTouch() { touch?.clear() }

    internal fun showProgress(message: String, phase: String) {
        val overlay = progress ?: VisualProgressOverlay(this).also { progress = it }
        overlay.show(message, phase)
    }

    internal fun fieldMatches(frame: VisualFrame, node: VisualNode, value: String): Boolean =
        onNode(frame, node) { current -> current.isEditable && current.text?.toString() == value }

    internal fun setText(frame: VisualFrame, node: VisualNode, value: String): Boolean = onNode(frame, node) { current ->
        current.isEditable && current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
    }

    internal fun submit(frame: VisualFrame, node: VisualNode): Boolean = onNode(frame, node) { current ->
        if (!current.isEditable || !current.isFocused || Build.VERSION.SDK_INT < 30) return@onNode false
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        if (current.supports(action) && current.performAction(action)) return@onNode true
        if (Build.VERSION.SDK_INT < 33) return@onNode false
        val editor = inputMethod ?: return@onNode false
        val info = editor.currentInputEditorInfo?.takeIf { editor.currentInputStarted && it.packageName == frame.pkg }
            ?: return@onNode false
        val command = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (command != EditorInfo.IME_ACTION_SEARCH && command != EditorInfo.IME_ACTION_GO) return@onNode false
        val connection = editor.currentInputConnection ?: return@onNode false
        connection.performEditorAction(command)
        true
    }

    internal suspend fun tap(frame: VisualFrame, node: VisualNode): Boolean {
        val center = PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY())
        if (progress?.moveAwayFrom(center) == true) delay(140L)
        if (progress?.contains(center) == true) return false
        if (node.ocr) {
            if (!isCurrent(frame) || revision != frame.revision) return false
        } else if (!onNode(frame, node) { it.isEnabled }) return false
        return gesture(frame, center, center, 65L)
    }

    internal fun semanticScroll(frame: VisualFrame, node: VisualNode, direction: String): Boolean = onNode(frame, node) {
        val directional = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        if (it.supports(directional) && it.performAction(directional)) return@onNode true
        if (direction == "left" || direction == "right") return@onNode false
        it.performAction(if (direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    internal suspend fun swipe(frame: VisualFrame, bounds: Rect, direction: String, fromEdge: Boolean = false): Boolean {
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val left = bounds.left + bounds.width() * .20f
        val right = bounds.right - bounds.width() * .20f
        val top = bounds.top + bounds.height() * .22f
        val bottom = bounds.bottom - bounds.height() * (if (fromEdge) .07f else .22f)
        val (from, to) = when (direction) {
            "up" -> PointF(x, bottom) to PointF(x, top)
            "down" -> PointF(x, top) to PointF(x, bottom)
            "left" -> PointF(right, y) to PointF(left, y)
            else -> PointF(left, y) to PointF(right, y)
        }
        if (progress?.moveAwayFrom(from) == true) delay(140L)
        if (progress?.contains(from) == true) return false
        return gesture(frame, from, to, 350L)
    }

    internal fun global(action: Int): Boolean = !locked() && performGlobalAction(action)

    private suspend fun gesture(frame: VisualFrame, from: PointF, to: PointF, duration: Long): Boolean {
        if (!isCurrent(frame)) return false
        val path = Path().apply {
            moveTo(from.x, from.y)
            if (from != to) lineTo(to.x, to.y)
        }
        return suspendCancellableCoroutine { pending ->
            val request = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            val accepted = dispatchGesture(request, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (pending.isActive) pending.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    touch?.clear()
                    if (pending.isActive) pending.resume(false)
                }
            }, null)
            if (accepted) {
                val indicator = touch ?: VisualTouchOverlay(this).also { touch = it }
                indicator.show(from, to, duration)
            } else if (pending.isActive) pending.resume(false)
        }
    }

    private fun isCurrent(frame: VisualFrame) = !locked() && withRoot {
        it.windowId == frame.window && it.packageName?.toString() == frame.pkg && !sensitive(it)
    } == true

    private fun onNode(frame: VisualFrame, node: VisualNode, use: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (locked() || node.ocr) return false
        return withRoot { root ->
            if (root.windowId != frame.window || root.packageName?.toString() != frame.pkg || sensitive(root)) return@withRoot false
            var current = root
            try {
                for (index in node.path) {
                    val child = current.getChild(index) ?: return@withRoot false
                    if (current !== root) current.recycle()
                    current = child
                    if (sensitive(current)) return@withRoot false
                }
                val rect = Rect().apply { current.getBoundsInScreen(this) }
                val text = if (current.isEditable) VisualLabels.clean(current.hintText) else VisualLabels.clean(current.text)
                val description = VisualLabels.clean(current.contentDescription)
                current.isVisibleToUser && current.isEnabled &&
                    Rect.intersects(rect, node.bounds) && current.viewIdResourceName.orEmpty() == node.resource &&
                    current.className?.toString()?.substringAfterLast('.').orEmpty() == node.role &&
                    text.ifBlank { description } == node.label && description == node.description && use(current)
            } finally {
                if (current !== root) current.recycle()
            }
        } == true
    }

    private fun <T> withRoot(use: (AccessibilityNodeInfo) -> T): T? {
        if (connected !== this) return null
        val root = rootInActiveWindow
        val available = windows
        try {
            val type = available.firstOrNull { it.id == root?.windowId }?.type
            if (root == null || type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                val active = available.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
                    ?: available.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
                    ?: return null
                try { return use(active) } finally { active.recycle() }
            }
            return use(root)
        } finally {
            root?.recycle()
            available.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    private fun windowBounds(id: Int): Rect {
        val entries = windows
        try {
            val bounds = Rect()
            entries.firstOrNull { it.id == id }?.getBoundsInScreen(bounds)
            return if (bounds.isEmpty) displayBounds() else bounds
        } finally {
            entries.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return Rect(wm.maximumWindowMetrics.bounds)
        val size = android.graphics.Point().also(wm.defaultDisplay::getRealSize)
        return Rect(0, 0, size.x, size.y)
    }

    private fun AccessibilityNodeInfo.supports(action: Int) = actionList.any { it.id == action }

    private fun sensitive(node: AccessibilityNodeInfo) =
        node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            (node.isEditable && PRIVATE_ID.containsMatchIn(node.viewIdResourceName.orEmpty().substringAfterLast('/')))

    internal companion object {
        var connected: CortexScreenService? = null
            private set
        private val PRIVATE_ID = Regex("""(?i)password|passcode|credit_card|card_number|one_time|otp_input|pin_code|private_key""")
        private val MANUAL_PACKAGES = Regex("""(?:com\.(?:android|google\.android)\.(?:permissioncontroller|packageinstaller|credentials)|com\.google\.android\.gms)""")
    }
}
