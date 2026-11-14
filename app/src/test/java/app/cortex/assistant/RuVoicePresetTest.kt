package app.cortex.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.net.URI

class RuVoicePresetTest {
    @Test fun selectsExportedProfileAndPreservesVoiceSettings() {
        val input = File.createTempFile("ruvoice-profile", ".json")
        try {
            input.writeText("""
                {
                  "app":"ruvoice",
                  "version":2,
                  "prefs":{"voice":"xenia","sr":48000,"pause_comma":130},
                  "profiles":{"active":"Кортекс","list":[
                    {"name":"Основной","prefs":{"voice":"aidar"}},
                    {"name":"Кортекс","prefs":{"voice":"ru/eugene","rate":{"f":0.8},"pitch":{"f":0.75},"volume":{"f":1.3}}}
                  ]},
                  "stress":{"Мой":"корте+кс","Выкл":"приве+т"},
                  "stress_off":["Выкл"]
                }
            """.trimIndent())
            val profile = RuVoicePreset.parse(input)
            assertEquals("eugene", profile.getString("profile"))
            assertEquals("ruvoice", profile.getString("engine"))
            assertEquals(0.8, profile.getDouble("rate"), 0.0001)
            assertEquals(0.75, profile.getDouble("pitch"), 0.0001)
            assertEquals(1.3, profile.getDouble("volume"), 0.0001)
            assertEquals(48000, profile.getInt("sample_rate"))
            assertEquals(130, profile.getInt("pause_comma"))
            assertEquals("корте+кс", profile.getJSONObject("stress").getString("кортекс"))
            assertFalse(profile.getJSONObject("stress").has("привет"))
        } finally { input.delete() }
    }

    @Test fun packageFilesStayInsideTheInstallDirectory() {
        val root = File("voice-package")
        assertEquals(File(root, "espeak-ng-data/voices/!v/f1"), packPath(root, "espeak-ng-data/voices/!v/f1"))
        for (path in listOf("../outside", "/outside", "sub/../../outside", "sub\\outside")) {
            assertThrows(IllegalArgumentException::class.java) { packPath(root, path) }
        }
        assertFalse(VoiceAsset.isGitHubVoiceUrl(URI("https://github.com.example.org/a/b/releases/download/v1/test.zip")))
    }
}
