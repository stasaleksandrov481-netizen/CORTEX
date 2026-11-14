package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.roundToInt
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
        delay(220L)
        val initial = await(7_000L, ::isLauncher) ?: return false
        report("Ищу «${app.label}» на рабочем столе…")
        appIcon(initial, app)?.let { if (launchIcon(initial, it, app, report)) return true }

        val deadline = SystemClock.uptimeMillis() + 58_000L
        val desktop = service.frame()?.takeIf(::isLauncher) ?: return false
        val drawer = if (drawerMarker(desktop)) desktop else openDrawer(desktop)
        if (drawer != null) {
            report("Ищу «${app.label}» в списке приложений…")
            appIcon(drawer, app)?.let { if (launchIcon(drawer, it, app, report)) return true }
            if (searchDrawer(drawer, app, report)) return true
            val latest = service.frame()?.takeIf(::isLauncher)
            val drawerPage = latest?.takeIf { drawerMarker(it) || page(it) == page(drawer) }
                ?: latest?.let { openDrawer(it) }
            if (drawerPage != null && walkPages(drawerPage, app, report, deadline, inDrawer = true)) return true
        }

        if (SystemClock.uptimeMillis() >= deadline || !service.global(AccessibilityService.GLOBAL_ACTION_HOME)) return false
        val workspace = await(3_500L) { isLauncher(it) && !drawerMarker(it) } ?: return false
        report("Проверяю страницы рабочего стола…")
        return walkPages(workspace, app, report, deadline, inDrawer = false)
    }

    private suspend fun openDrawer(frame: VisualFrame): VisualFrame? {
        val button = frame.tree.firstOrNull { it.clickable && it.enabled &&
            listOf(it.label, it.description).any { text -> VisualLabels.normalize(text) in ALL_APPS } }
        if (button != null && service.tap(frame, button)) {
            changedPage(frame)?.takeIf(::isLauncher)?.let { return it }
        }
        val current = service.frame()?.takeIf(::isLauncher) ?: return null
        if (!service.swipe(current, current.bounds, "up", fromEdge = true)) return null
        return changedPage(current)?.takeIf(::isLauncher)
    }

    private suspend fun searchDrawer(drawer: VisualFrame, app: VisualApp, report: (String) -> Unit): Boolean {
        val search = drawer.tree.firstOrNull(::appSearch) ?: return false
        val editorFrame: VisualFrame
        val editor: VisualNode
        if (search.editable) {
            editorFrame = drawer
            editor = search
        } else {
            if (!service.tap(drawer, search)) return false
            editorFrame = await(2_000L) { isLauncher(it) && it.tree.any(::searchEditor) } ?: return false
            editor = editorFrame.tree.first(::searchEditor)
        }
        report("Ищу «${app.label}» через поиск приложений…")
        if (!service.setText(editorFrame, editor, app.label)) return false
        val results = await(3_000L) { isLauncher(it) && appIcon(it, app) != null }
        val icon = results?.let { appIcon(it, app) }
        if (results != null && icon != null && launchIcon(results, icon, app, report)) return true

        val current = service.frame()?.takeIf(::isLauncher) ?: return false
        current.tree.firstOrNull(::searchEditor)?.let { service.setText(current, it, "") }
        if (current.tree.any { it.editable && it.focused }) {
            service.global(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(260L)
        }
        return false
    }

    private suspend fun walkPages(
        start: VisualFrame, app: VisualApp, report: (String) -> Unit, deadline: Long, inDrawer: Boolean
    ): Boolean {
        var frame = start
        val primary = if (inDrawer) containerAxis(frame) ?: frame.scrollAxis ?: VisualScrollAxis.HORIZONTAL
            else VisualScrollAxis.HORIZONTAL
        val alternate = if (primary == VisualScrollAxis.HORIZONTAL) VisualScrollAxis.VERTICAL else VisualScrollAxis.HORIZONTAL
        val axes = if (inDrawer && containerAxis(frame) == null) listOf(primary, alternate) else listOf(primary)
        val checked = mutableSetOf<Page>()
        for (axis in axes) {
            var confirmedAxis = false
            val directions = if (axis == VisualScrollAxis.HORIZONTAL) listOf("left", "right") else listOf("up", "down")
            for (direction in directions) {
                // A reverse pass may have to cross known pages to reach the other end.
                val pass = mutableSetOf<Page>()
                while (isLauncher(frame) && SystemClock.uptimeMillis() < deadline) {
                    val here = page(frame)
                    if (!pass.add(here)) break
                    if (checked.add(here)) {
                        val icon = appIcon(frame, app)
                        if (icon != null) {
                            if (launchIcon(frame, icon, app, report)) return true
                            frame = service.frame()?.takeIf(::isLauncher) ?: return false
                            report(if (inDrawer) "Проверяю список приложений…" else "Проверяю страницы рабочего стола…")
                        }
                    }
                    val area = if (inDrawer) scrollArea(frame, axis) else frame.bounds
                    if (!service.swipe(frame, area, direction, duration = if (axis == VisualScrollAxis.VERTICAL) 520L else 340L)) break
                    val next = changedPage(frame)?.takeIf(::isLauncher) ?: break
                    if (axis == VisualScrollAxis.VERTICAL && drawerMarker(frame) && !drawerMarker(next)) return false
                    if (next.scrollRevision > frame.scrollRevision && next.scrollAxis == axis) confirmedAxis = true
                    frame = next
                }
            }
            // The launcher can also reveal its scroll axis in a real scroll event.
            if (confirmedAxis || containerAxis(frame) == axis) break
        }
        val icon = appIcon(frame, app)
        return SystemClock.uptimeMillis() < deadline && icon != null && launchIcon(frame, icon, app, report)
    }

    private fun containerAxis(frame: VisualFrame): VisualScrollAxis? =
        frame.tree.filter { it.scrollAxis != null && it.bounds.height() >= frame.bounds.height() * .3f }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.scrollAxis

    private fun scrollArea(frame: VisualFrame, axis: VisualScrollAxis): Rect =
        frame.tree.filter { it.scrollable && (it.scrollAxis == axis || it.scrollAxis == null) &&
            it.bounds.width() >= frame.bounds.width() * .53f && it.bounds.height() >= frame.bounds.height() * .34f }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.bounds ?: frame.bounds

    private fun isLauncher(frame: VisualFrame) = frame.pkg in home

    private fun drawerMarker(frame: VisualFrame) = frame.tree.any { node ->
        val id = node.resource.substringAfterLast('/')
        id in DRAWER_RESOURCES || node.editable && appSearch(node)
    }

    private fun appSearch(node: VisualNode): Boolean {
        if (!node.enabled || (!node.editable && !node.clickable)) return false
        val id = node.resource.substringAfterLast('/')
        val label = VisualLabels.normalize("${node.label} ${node.description}")
        return id in SEARCH_RESOURCES || label.contains("поиск приложени") || label.contains("найти приложени") ||
            label.contains("search apps") || label.contains("search applications")
    }

    private fun searchEditor(node: VisualNode) = node.editable && node.enabled && (node.focused || appSearch(node))

    private suspend fun changedPage(before: VisualFrame): VisualFrame? {
        val old = page(before)
        delay(330L)
        var candidate: Page? = null
        return await(1_800L) { frame ->
            val next = page(frame)
            val stable = next == candidate
            candidate = next
            next != old && stable
        }
    }

    private fun page(frame: VisualFrame): Page {
        val unit = (frame.bounds.width() / 44f).coerceAtLeast(4f)
        val labels = frame.tree.asSequence().filter { !it.editable && it.label.isNotBlank() &&
            (it.clickable || it.role == "TextView" || it.selected) && !CLOCK.matches(it.label) }.map { node ->
            PageItem(VisualLabels.normalize(node.label), ((node.bounds.exactCenterX() - frame.bounds.left) / unit).roundToInt(),
                ((node.bounds.exactCenterY() - frame.bounds.top) / unit).roundToInt(), node.selected)
        }.distinct().sortedWith(compareBy(PageItem::y, PageItem::x, PageItem::label, PageItem::selected)).toList()
        return Page(frame.pkg, frame.window, drawerMarker(frame), labels)
    }

    private data class Page(val pkg: String, val window: Int, val drawer: Boolean, val items: List<PageItem>)
    private data class PageItem(val label: String, val x: Int, val y: Int, val selected: Boolean)

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
        private val CLOCK = Regex("""\d{1,2}[:.]\d{2}(?:\s*[AaPp][Mm])?""")
        private val SEARCH_RESOURCES = setOf("apps_search_area", "apps_search_box", "apps_search_bar", "apps_search_input",
            "search_apps", "search_apps_view", "search_apps_input", "search_container_all_apps", "search_field")
        private val DRAWER_RESOURCES = setOf("apps_list_view", "all_apps_view", "all_apps", "apps_view",
            "apps_grid", "app_drawer", "apps_paged_view", "apps_content_view", "search_container_all_apps")
        private val APP_NAMES = mapOf("тг" to "telegram", "телеграм" to "telegram", "хром" to "chrome",
            "ютуб" to "youtube", "файлы" to "files", "проводник" to "files", "камера" to "camera")
    }
}
