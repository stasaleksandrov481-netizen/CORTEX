package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.delay

internal data class VisualApp(val label: String, val pkg: String)

internal class VisualNavigation(context: Context, private val service: CortexScreenService) {
    private val manager = context.packageManager
    val home = service.homePackages()
    val apps: List<VisualApp> = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { VisualApp(it.loadLabel(manager).toString(), it.activityInfo.packageName) }.distinctBy { it.pkg }
        .filterNot { it.pkg == context.packageName }

    fun resolve(target: String): VisualApp? {
        apps.singleOrNull { it.pkg.equals(target.trim(), true) }?.let { return it }
        val normalized = VisualLabels.normalize(target)
        apps.singleOrNull { normalized == VisualLabels.normalize(it.label) }?.let { return it }
        val alias = APP_NAMES[normalized]
        if (alias != null) apps.singleOrNull { VisualLabels.normalize(it.label) == alias }?.let { return it }
        val ranked = apps.map { it to VisualLabels.score(it.label, target) }.filter { it.second >= 60 }
            .sortedByDescending { it.second }
        if (ranked.isNotEmpty() && ranked.drop(1).none { it.second >= ranked.first().second - 5 }) return ranked.first().first
        if (VisualLabels.normalize(target) !in SETTINGS_NAMES) return null
        val info = manager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        return VisualApp(info.loadLabel(manager).toString(), info.activityInfo.packageName)
    }

    suspend fun open(app: VisualApp, report: (String) -> Unit): Boolean {
        if (home.isEmpty()) return false
        report("Перехожу на рабочий стол…")
        if (!service.global(AccessibilityService.GLOBAL_ACTION_HOME)) return false
        var frame = await(9_000L) { it.pkg in home } ?: return false
        report("Ищу «${app.label}»…")
        for (page in 0..2) {
            val icon = appIcon(frame, app)
            if (icon != null) return launchIcon(frame, icon, app, report)
            if (page == 2 || !service.swipe(frame, frame.bounds, "left")) break
            frame = after(frame, scrolled = true) ?: break
        }
        val drawer = frame.nodes.firstOrNull { it.clickable && it.enabled &&
            (VisualLabels.normalize(it.label) in ALL_APPS || VisualLabels.normalize(it.description) in ALL_APPS) }
        val openedDrawer = if (drawer != null && service.tap(frame, drawer)) after(frame) else null
        frame = openedDrawer ?: if (service.swipe(frame, frame.bounds, "up", fromEdge = true)) {
            after(frame, scrolled = true) ?: frame
        } else frame
        if (frame.pkg !in home) return false
        val visited = mutableSetOf<Int>()
        var searched = false
        repeat(8) {
            if (frame.pkg !in home) return false
            val icon = appIcon(frame, app)
            if (icon != null) return launchIcon(frame, icon, app, report)
            if (!searched && frame.tree.any { it.editable && it.enabled }) {
                val field = frame.tree.first { it.editable && it.enabled }
                searched = true
                if (!field.focused && service.tap(frame, field)) delay(180L)
                if (service.setText(frame, field, app.label)) {
                    frame = after(frame) ?: service.frame() ?: frame
                    return@repeat
                }
            }
            if (!visited.add(frame.structure())) return false
            val before = frame
            val direction = if (it % 2 == 0) "up" else "left"
            if (!service.swipe(frame, frame.bounds, direction)) return false
            frame = after(before, scrolled = true) ?: before
        }
        return false
    }

    private suspend fun launchIcon(frame: VisualFrame, icon: VisualNode, app: VisualApp, report: (String) -> Unit): Boolean {
        report("Открываю «${app.label}»…")
        return service.tap(frame, icon) && await(9_000L) { it.pkg == app.pkg } != null
    }

    private fun appIcon(frame: VisualFrame, app: VisualApp): VisualNode? {
        val exact = frame.target(app.label) { it.clickable || it.role == "TextView" }
        if (exact != null && maxOf(VisualLabels.score(exact.label, app.label),
                VisualLabels.score(exact.description, app.label)) >= 82) return exact
        return null
    }

    suspend fun await(timeout: Long = 4_500L, accept: (VisualFrame) -> Boolean): VisualFrame? {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            val frame = service.frame()
            if (frame != null && accept(frame)) return frame
            delay(220L)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    suspend fun after(before: VisualFrame, scrolled: Boolean = false): VisualFrame? {
        val beforeStructure = before.structure()
        delay(240L)
        return await {
            it.pkg != before.pkg || it.window != before.window || it.structure() != beforeStructure ||
                (scrolled && it.scrollRevision > before.scrollRevision)
        }
    }

    companion object {
        private val SETTINGS_NAMES = setOf("настройки", "настройках", "settings")
        private val ALL_APPS = setOf("all apps", "apps", "app drawer", "все приложения", "приложения", "меню приложений")
        private val APP_NAMES = mapOf("тг" to "telegram", "телеграм" to "telegram", "хром" to "chrome",
            "ютуб" to "youtube", "файлы" to "files", "проводник" to "files", "камера" to "camera")
    }
}
