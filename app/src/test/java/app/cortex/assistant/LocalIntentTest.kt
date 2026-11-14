package app.cortex.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntentTest {
    private val installed: (String) -> Boolean = {
        it.equals("Telegram", true) || it.equals("телеграме", true) || it.equals("Test Chat", true)
    }

    @Test fun spokenWifiAndHotspotFaultIsARepairIntent() {
        val request = NetworkRequest.parse(
            "Кортекс, бро помоги мне у меня вайфай выключается и включается модем"
        )
        assertEquals(NetworkRequest(NetworkRequest.Mode.REPAIR, wifi = true, hotspot = true), request)
    }

    @Test fun networkStatusAndExplicitSwitchesStayLocal() {
        assertEquals(NetworkRequest.Mode.CHECK, NetworkRequest.parse("Почему отключается Wi-Fi?")?.mode)
        assertEquals(NetworkRequest.Mode.CHECK, NetworkRequest.parse("Проверь точку доступа")?.mode)
        assertEquals(NetworkRequest.Mode.ENABLE, NetworkRequest.parse("Включи вай-фай")?.mode)
        assertEquals(NetworkRequest.Mode.DISABLE, NetworkRequest.parse("Выключи режим модема")?.mode)
        assertEquals(NetworkRequest.Mode.OPEN, NetworkRequest.parse("Открой настройки Wi-Fi")?.mode)
        assertNull(NetworkRequest.parse("Как настроить Wi-Fi на другом телефоне?"))
        assertNull(NetworkRequest.parse("Напиши другу в Telegram «Помоги, не работает Wi-Fi»"))
        assertNull(NetworkRequest.parse("Включи Wi-Fi и выключи фонарик"))
    }

    @Test fun conversationAsksForExactRecipientAndTextBeforeActing() {
        val intent = requireNotNull(ConversationRequest.parse(
            "Кортекс, открой Telegram, зайди в переписку с другом и напиши сообщение", installed
        ))
        assertEquals("", intent.recipient)
        assertEquals("", intent.message)
        assertTrue(intent.question.contains("контакт"))
        val next = intent.withAnswer("Анна")
        assertEquals("Анна", next.recipient)
        assertTrue(next.question.contains("текст"))
        assertEquals("Привет!", next.withAnswer("«Привет!»").message)
        assertTrue(next.withAnswer("«Привет!»").send)
    }

    @Test fun messageBodyAndDraftFlagStayDistinct() {
        val direct = requireNotNull(ConversationRequest.parse(
            "Открой Test Chat, зайди в переписку с Анна и напиши сообщение: «Привет, не отправляй пароль»", installed
        ))
        assertEquals("Test Chat", direct.app)
        assertEquals("Анна", direct.recipient)
        assertEquals("Привет, не отправляй пароль", direct.message)
        assertTrue(direct.send)
        val draft = requireNotNull(ConversationRequest.parse(
            "Напиши Анне в Телеграме: «Скоро буду» без отправки", installed
        ))
        assertEquals("Анне", draft.recipient)
        assertEquals("Скоро буду", draft.message)
        assertFalse(draft.send)
        val followed = direct.copy(message = "").withAnswer("«Скоро буду» без отправки")
        assertEquals("Скоро буду", followed.message)
        assertFalse(followed.send)
        assertNull(ConversationRequest.parse("Как отправить сообщение в Telegram?", installed))
    }

    @Test fun genericDeviceDiagnosisIsAnAction() {
        assertTrue(VisualRequest.isAction("Кортекс, бро, проверь настройки телефона"))
        assertTrue(VisualRequest.isAction("Кортекс, открой Telegram"))
        assertEquals("Telegram", VisualRequest.singleApp("Кортекс, открой Telegram"))
        assertNull(VisualRequest.singleApp("Открой Telegram и найди Анну"))
    }
}
