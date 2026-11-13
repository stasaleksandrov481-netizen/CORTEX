package app.cortex.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CortexWakeTurnTest {
    @Test fun fragmentsOfOneRequestAreSubmittedTogetherAfterSilence() {
        val submitted = mutableListOf<String>()
        val turn = CortexWakeTurn({}, { submitted += it }, {})
        turn.result(listOf("Кортекс, включи фонарик"), 100, segmented = true)
        turn.result(listOf("открой тг"), 700, segmented = true)
        turn.result(listOf("выключи фонарик, закрой тг и выключи экран"), 1900, segmented = true)
        turn.expire(2500)
        assertTrue(submitted.isEmpty())
        turn.expire(3250)
        assertEquals(listOf("включи фонарик открой тг выключи фонарик, закрой тг и выключи экран"), submitted)
    }

    @Test fun repeatedFinalTranscriptDoesNotRepeatItsFirstFragment() {
        val submitted = mutableListOf<String>()
        val turn = CortexWakeTurn({}, { submitted += it }, {})
        turn.result(listOf("Кортекс"), 100, segmented = true)
        turn.result(listOf("включи фонарик"), 650, segmented = true)
        turn.result(listOf("Кортекс, включи фонарик и открой тг"), 950)
        turn.expire(2300)
        assertEquals(listOf("включи фонарик и открой тг"), submitted)
    }
}
