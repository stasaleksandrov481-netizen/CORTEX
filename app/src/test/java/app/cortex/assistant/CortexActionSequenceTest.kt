package app.cortex.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CortexActionSequenceTest {
    private val telegram = "org.telegram.messenger"
    private val appLookup: (String) -> String? = { if (it in listOf("тг", "телеграм")) telegram else null }
    private val examples = listOf(
        "Кортекс, включить фонарик, открыть тг, выключить фонарик, закрыть тг и выключить экран",
        "включи фонарик открой тг выключи фонарик закрой тг выключи экран",
        "включи фонарик открой тг выключи фонарик закрой тг и выключи экран",
        "Включи фонарик, потом открой Телеграм, затем выключи фонарик и сверни ТГ, после этого погаси экран"
    )

    @Test fun voiceRequestsBecomeTheSameOrderedPlan() {
        for (text in examples) {
            val plan = requireNotNull(CortexLocalCommands.parse(text, appLookup))
            assertEquals(text, listOf("torch", "app", "torch", "close_app", "lock_screen"), plan.map { it.getString("kind") })
            assertEquals("on", plan[0].getString("mode"))
            assertEquals(telegram, plan[1].getString("target"))
            assertEquals("off", plan[2].getString("mode"))
            assertEquals(telegram, plan[3].getString("target"))
        }
    }

    @Test fun unsupportedClauseDefersWholePlanToPlanner() {
        assertNull(CortexLocalCommands.parse("Включи фонарик, зайди в ТГ и открой переписку с Машей", appLookup))
        assertNull(CortexLocalCommands.parse("Как выключить фонарик", appLookup))
    }

    @Test fun standalonePhotoAndCameraOpeningHaveDistinctActions() {
        val camera = requireNotNull(CortexLocalCommands.parse("Открой камеру", appLookup)).single()
        assertEquals("app", camera.getString("kind"))
        assertEquals("camera", camera.getString("target"))
        val photo = requireNotNull(CortexLocalCommands.parse("Открой камеру и сделай фото", appLookup)).single()
        assertEquals("capture_photo", photo.getString("kind"))
        val selfie = requireNotNull(CortexLocalCommands.parse("Сфоткайся", appLookup)).single()
        assertEquals("front", selfie.getString("lens"))
    }

    @Test fun queueWaitsForEachResultAndContinuesAcrossAppSwitch() {
        val plan = requireNotNull(CortexLocalCommands.parse(examples.first(), appLookup))
        val callbacks = mutableListOf<(JSONObject) -> Unit>()
        var final: CortexActionSequence.Result? = null
        CortexActionSequence(plan, { true }, { command, index, total, done ->
            assertEquals(5, total)
            assertEquals(callbacks.size + 1, index)
            assertEquals(plan[index - 1], command)
            callbacks += done
        }) { final = it }.start()
        assertEquals(1, callbacks.size)
        callbacks[0](outcome("done").put("enabled", true))
        assertEquals(2, callbacks.size)
        callbacks[1](outcome("opened").put("verified", true).put("name", "Telegram"))
        callbacks[1](outcome("done"))
        assertEquals(3, callbacks.size)
        callbacks[2](outcome("done").put("enabled", false))
        callbacks[3](outcome("opened").put("verified", true).put("name", "Telegram"))
        assertNull(final)
        callbacks[4](outcome("done"))
        assertEquals(5, final?.observations?.length())
        assertTrue(final?.ok == true)
        assertTrue(final?.external == true)
    }

    @Test fun unconfirmedStepDoesNotSilenceRemainingStepsOrClaimSuccess() {
        val plan = requireNotNull(CortexLocalCommands.parse(examples.first(), appLookup))
        val executed = mutableListOf<String>()
        var final: CortexActionSequence.Result? = null
        CortexActionSequence(plan, { true }, { command, index, _, done ->
            executed += command.getString("kind")
            done(outcome(if (index == 1) "requested" else "done"))
        }) { final = it }.start()
        assertEquals(listOf("torch", "app", "torch", "close_app", "lock_screen"), executed)
        assertFalse(requireNotNull(final).ok)
        assertTrue(requireNotNull(final).text.contains("Подтверждения результата пока нет"))
    }

    @Test fun permissionHandoffOrExplicitCancellationStopsTheQueue() {
        for (status in listOf("panel", "cancelled")) {
            var final: CortexActionSequence.Result? = null
            CortexActionSequence(listOf(step("screen_task"), step("torch")), { true }, { _, index, _, done ->
                assertEquals(1, index)
                done(outcome(status))
            }) { final = it }.start()
            assertEquals(1, final?.observations?.length())
            assertTrue(final?.blocked == true)
            assertTrue(final?.text?.contains("Осталось шагов: 1") == true)
        }
    }

    @Test fun lateCallbackAfterStopDoesNotStartNextStep() {
        var active = true
        var callback: ((JSONObject) -> Unit)? = null
        CortexActionSequence(listOf(step("torch"), step("app")), { active }, { _, index, _, done ->
            assertEquals(1, index)
            callback = done
        }) { error("Stopped plan cannot complete") }.start()
        assertNotNull(callback)
        active = false
        callback?.invoke(outcome("done"))
    }

    private fun step(kind: String) = JSONObject().put("kind", kind)
    private fun outcome(status: String) = JSONObject().put("status", status)
}
