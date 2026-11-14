package app.cortex.assistant

import android.graphics.Rect

/** Uses the same search, result, editor and send controls across accessible messaging apps. */
internal class ConversationNavigation(
    private val screen: CortexScreenService,
    private val navigation: VisualNavigation,
    private val confirm: suspend (String, String, String) -> Boolean
) {
    suspend fun run(request: ConversationRequest, report: (String) -> Unit): CortexActionEngine.Result {
        fun result(text: String, ok: Boolean = false) = CortexActionEngine.Result(text, ok)
        val app = navigation.resolve(request.app) ?: return result("Приложение «${request.app}» не найдено.")
        if (!navigation.open(app, report)) return result("Android не подтвердил открытие «${app.label}».")
        var frame = navigation.await { usable(it, app.pkg) }
            ?: return result("Приложение открылось, но экран переписки пока недоступен.")
        if (header(frame, request.recipient) == null || composer(frame) == null) {
            report("Ищу переписку «${request.recipient}»…")
            if (searchField(frame) == null) {
                val search = findSearch(frame) ?: return result("В «${app.label}» не нашёл кнопку поиска переписки. Откройте нужный чат и повторите команду.")
                if (!activate(frame, search)) return result("Приложение не открыло поиск.")
                frame = navigation.await(2_600L) { usable(it, app.pkg) && searchField(it) != null }
                    ?: return result("Открыл поиск, но поле ввода пока недоступно.")
            }
            val field = searchField(frame) ?: return result("Поле поиска изменилось. Повторите команду.")
            val before = frame.structure()
            if (!screen.setText(frame, field, request.recipient)) return result("Приложение не приняло запрос поиска.")
            val matching = navigation.await(3_600L) {
                usable(it, app.pkg) && it.structure() != before && exactResult(it, request.recipient) != null
            } ?: navigation.await(1_100L) { usable(it, app.pkg) && exactResult(it, request.recipient) != null }
                ?: return result("Не смог однозначно найти «${request.recipient}». Уточните точное имя или @username.")
            val row = exactResult(matching, request.recipient) ?: return result("Результаты поиска изменились.")
            if (!activate(matching, row)) return result("Переписка не открылась.")
            frame = navigation.await(3_000L) {
                usable(it, app.pkg) && header(it, request.recipient) != null && composer(it) != null
            } ?: return result("Приложение не подтвердило получателя «${request.recipient}». Сообщение осталось неотправленным.")
        }
        if (!navigation.isActive(frame)) return result("Экран переписки изменился до ввода.")
        val editor = composer(frame) ?: return result("Поле сообщения недоступно.")
        if (!screen.fieldEmpty(frame, editor)) return result("В переписке уже есть текст. Оставил его без изменений.")
        if (request.message.any { it.code < 32 && it !in "\n\t" }) return result("В тексте сообщения есть недопустимые символы.")
        report("Ввожу сообщение…")
        if (!screen.setText(frame, editor, request.message)) return result("Приложение не приняло текст сообщения.")
        fun draftVisible(current: VisualFrame) = usable(current, app.pkg) && header(current, request.recipient) != null &&
            composer(current)?.let { screen.fieldMatches(current, it, request.message) } == true
        val drafted = navigation.await(2_000L, ::draftVisible)
            ?: return result("Приложение не подтвердило текст сообщения. Проверьте поле ввода.")
        if (!request.send) return result("Черновик для «${request.recipient}» готов в ${app.label}.", true)
        if (composer(drafted)?.let { findSend(drafted, it) } == null) return result("Ввёл текст для «${request.recipient}». Кнопку отправки не удалось проверить.")
        report("Жду подтверждения отправки…")
        if (!confirm("Отправить сообщение?", "Кому: ${request.recipient}\nПриложение: ${app.label}\n\n${request.message.take(1_600)}", "Отправить")) {
            return result("Отправка отменена. Сообщение осталось в поле ввода.")
        }
        val confirmed = navigation.await(3_000L, ::draftVisible)
            ?: return result("Получатель или текст изменился. Отправка отменена.")
        val freshEditor = composer(confirmed) ?: return result("Поле ввода изменилось. Отправка отменена.")
        val button = findSend(confirmed, freshEditor) ?: return result("Кнопка отправки изменилась.")
        report("Отправляю сообщение…")
        if (!activate(confirmed, button)) return result("Приложение отклонило нажатие кнопки отправки.")
        val verified = navigation.await(3_300L) { current ->
            usable(current, app.pkg) && header(current, request.recipient) != null &&
                composer(current)?.let { screen.fieldEmpty(current, it) } == true &&
                current.tree.any { !it.editable && VisualLabels.clean(request.message) == it.label &&
                    it.bounds.top >= topLimit(current) }
        }
        return if (verified != null) result("Сообщение появилось в переписке «${request.recipient}».", true)
        else result("Нажал «Отправить». Проверьте переписку — результат пока не подтверждён.")
    }

    private suspend fun activate(frame: VisualFrame, node: VisualNode) =
        screen.click(frame, node) || screen.tap(frame, node)

    private fun usable(frame: VisualFrame, pkg: String) =
        frame.pkg == pkg && !frame.secure && !frame.hasPrivateFields

    private fun findSearch(frame: VisualFrame) = frame.tree.filter { node ->
        node.enabled && !node.editable && match(node, searchNames) && node.bounds.top < topLimit(frame)
    }.preferClick()

    private fun searchField(frame: VisualFrame): VisualNode? = frame.tree.filter { node ->
        node.enabled && node.editable && match(node, searchNames)
    }.singleOrNull()

    private fun exactResult(frame: VisualFrame, name: String): VisualNode? {
        val field = searchField(frame) ?: return null
        val results = frame.tree.filter { it.enabled && !it.editable && it.bounds.top >= field.bounds.bottom &&
            exact(it, name) }.distinctBy { it.bounds }
        val node = results.singleOrNull() ?: return null
        val parent = frame.tree.filter { it.clickable && it.enabled && contains(it.bounds, node.bounds) &&
            it.path.size < node.path.size && node.path.take(it.path.size) == it.path &&
            it.bounds.height() <= node.bounds.height() * 5 }.maxByOrNull { it.path.size }
        return parent ?: node
    }

    private fun header(frame: VisualFrame, name: String) = frame.tree.firstOrNull {
        it.enabled && !it.editable && it.bounds.top < topLimit(frame) && exact(it, name)
    }

    private fun composer(frame: VisualFrame): VisualNode? = frame.tree.filter {
        it.enabled && it.editable && !match(it, searchNames) &&
            (match(it, editorNames) || it.bounds.centerY() > frame.bounds.top + frame.bounds.height() * .52)
    }.singleOrNull()

    private fun findSend(frame: VisualFrame, editor: VisualNode) = frame.tree.filter {
        it.enabled && !it.editable && match(it, sendNames) &&
            it.bounds.bottom >= editor.bounds.top && it.bounds.top <= editor.bounds.bottom + editor.bounds.height()
    }.preferClick()

    private fun List<VisualNode>.preferClick() = filter { it.clickable }.singleOrNull() ?: singleOrNull()

    private fun match(node: VisualNode, names: Set<String>) = listOf(node.label, node.description,
        node.resource.substringAfterLast('/')).any { VisualLabels.normalize(it) in names }

    private fun exact(node: VisualNode, name: String) =
        if (name.startsWith('@')) listOf(node.label, node.description).any { it.trim().equals(name, ignoreCase = true) }
        else VisualLabels.normalize(name).let { value ->
            listOf(node.label, node.description.substringBefore('\n')).any { VisualLabels.normalize(it) == value }
        }

    private fun contains(outer: Rect, inner: Rect) = outer.contains(inner.left, inner.top, inner.right, inner.bottom)

    private fun topLimit(frame: VisualFrame) = frame.bounds.top + frame.bounds.height() * .24

    private companion object {
        val searchNames = setOf("search", "search chats", "search messages", "поиск", "найти", "поиск чатов", "поиск сообщений",
            "search button", "search field", "search src text", "search input", "menu search", "action search", "action bar search")
        val editorNames = setOf("message", "message input", "type a message", "write a message", "сообщение", "напишите сообщение",
            "введите сообщение", "поле сообщения", "compose text", "chat input", "entry")
        val sendNames = setOf("send", "send message", "send button", "отправить", "отправить сообщение", "отправка", "message send")
    }
}
