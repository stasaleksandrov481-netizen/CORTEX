package app.cortex.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualPlanTest {
    private val installed: (String) -> Boolean = { it.equals("Telegram", true) || it.equals("Settings", true) }

    @Test fun mixedPhoneRequestKeepsItsOriginalOrder() {
        val steps = requireNotNull(VisualRequest.local(
            "Включи фонарик, открой Telegram, выключи фонарик, закрой Telegram и выключи экран", installed
        ))
        assertEquals(listOf(
            VisualAction.QUICK_SETTINGS, VisualAction.SET, VisualAction.OPEN, VisualAction.QUICK_SETTINGS,
            VisualAction.SET, VisualAction.HOME, VisualAction.LOCK
        ), steps.map { it.action })
        assertEquals(listOf("on", "off"), steps.filter { it.action == VisualAction.SET }.map { it.value })
    }

    @Test fun explicitScreenPathsUseGenericLabels() {
        val steps = requireNotNull(VisualRequest.local("Открой Settings → Display → Dark theme", installed))
        assertEquals(listOf("Settings", "Display", "Dark theme"), steps.map { it.target })
        assertEquals(listOf(VisualAction.OPEN, VisualAction.TAP, VisualAction.TAP), steps.map { it.action })
        assertNull(VisualRequest.local("Открой приложение, которого нет", installed))
        assertTrue(VisualRequest.isAction("Сделай фото"))
        assertFalse(VisualRequest.isAction("Как выключить экран?"))
    }

    @Test fun plannerRejectsUnknownOperationsAndInvalidParameters() {
        assertNull(VisualPlan.parse("""{"steps":[{"action":"tap","target":"OK","x":12}]}"""))
        assertNull(VisualPlan.parse("""{"steps":[{"action":"set","target":"Wi-Fi","value":"toggle"}]}"""))
        assertNull(VisualPlan.parse("""{"steps":[{"action":"open","target":123}]}"""))
        assertNotNull(VisualPlan.parse("""{"steps":[{"action":"type","value":"Привет!"},{"action":"read"}]}"""))
        assertNotNull(VisualPlan.parse("""{"question":"Где открыт аккаунт?"}"""))
        assertTrue(requireNotNull(VisualPlan.parse("""{"done":true}""")).done)
        assertNull(VisualPlan.parse("""{"steps":[{"action":"read"}],"done":true}"""))
    }

    @Test fun quotedTextAndSimpleGesturesStayLocal() {
        val steps = requireNotNull(VisualRequest.local(
            "Введи текст «Привет, потом открой файл» и свайпни вверх, вернись назад", installed
        ))
        assertEquals(listOf(VisualAction.TYPE, VisualAction.SWIPE, VisualAction.BACK), steps.map { it.action })
        assertEquals("Привет, потом открой файл", steps.first().value)
        assertEquals("up", steps[1].value)
    }

    @Test fun finalSubmissionIsManualForGradedTasksAndConfirmedForMessages() {
        assertEquals(VisualRisk.MANUAL, VisualSafety.risk("Выполни тест на Учи.ру", VisualStep(VisualAction.TAP),
            "Завершить", "button", "Button"))
        assertEquals(VisualRisk.CONFIRM, VisualSafety.risk("Напиши сообщение", VisualStep(VisualAction.TAP),
            "", "toolbar_button", "Button"))
        assertEquals(VisualRisk.CONFIRM, VisualSafety.risk("Нажми кнопку", VisualStep(VisualAction.TAP),
            "Send", "action", "Button"))
        assertEquals(VisualRisk.NORMAL, VisualSafety.risk("Напиши сообщение", VisualStep(VisualAction.TAP),
            "Поиск", "toolbar_search", "ImageButton"))
    }
}
