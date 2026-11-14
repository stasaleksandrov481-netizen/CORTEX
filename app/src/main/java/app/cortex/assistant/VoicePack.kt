package app.cortex.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale

private val packId = Regex("[a-z0-9][a-z0-9-]{1,47}")
private val digest = Regex("[a-f0-9]{64}")
private val reserved = setOf("android", "ruvoice-eugene", "ruvoice-aidar", "ruvoice-xenia")
private val languageTag = Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}")

internal data class VoiceAsset(val url: URI, val bytes: Long, val sha256: String, val path: String) {
    companion object {
        fun read(source: JSONObject, path: String = ""): VoiceAsset {
            val url = URI(source.getString("url"))
            require(isGitHubVoiceUrl(url))
            val bytes = source.getLong("size_bytes")
            val hash = source.getString("sha256")
            require(bytes in 1..170_000_000L && digest.matches(hash))
            return VoiceAsset(url, bytes, hash, path)
        }

        fun isGitHubVoiceUrl(url: URI): Boolean {
            if (url.scheme != "https" || url.rawUserInfo != null || url.port != -1 ||
                url.rawQuery != null || url.rawFragment != null || url.toString().length > 1400) return false
            val path = url.rawPath.orEmpty()
            return when (url.host) {
                "github.com" -> Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+").matches(path)
                "raw.githubusercontent.com" -> Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[^/]+/.+").matches(path)
                else -> false
            }
        }
    }
}

internal data class VoiceEntry(
    val id: String, val title: String, val description: String, val version: Int,
    val kind: String, val assets: List<VoiceAsset>, val speaker: String?
) {
    val size = assets.sumOf { it.bytes }
    val revision = "$version-${assets.first().sha256.take(16)}-${assets.last().sha256.take(16)}"

    fun toJson() = JSONObject()
        .put("id", id).put("title", title).put("description", description).put("version", version)
        .put("size", size).put("revision", revision)

    companion object {
        fun read(item: JSONObject, bundled: Boolean = false): VoiceEntry {
            val id = item.getString("id")
            val title = item.getString("title").trim()
            val version = item.getInt("version")
            require(packId.matches(id) && (bundled || id !in reserved) && title.length in 1..80 && version in 1..1_000_000)
            val kind = item.getString("kind")
            require(kind in setOf("zip", "ruvoice-preset") || bundled && kind == "ruvoice-voice")
            val speaker = if (kind == "ruvoice-voice") item.getString("speaker") else null
            val assets = when (kind) {
                "ruvoice-voice" -> listOf(RuVoicePreset.russianModel)
                "ruvoice-preset" -> listOf(VoiceAsset.read(item), RuVoicePreset.russianModel)
                else -> listOf(VoiceAsset.read(item))
            }
            return VoiceEntry(id, title, item.optString("description").take(220), version, kind, assets, speaker)
        }
    }
}

internal fun JSONArray.voiceEntries(bundled: Boolean = false): List<VoiceEntry> =
    (0 until minOf(length(), 100)).mapNotNull { index ->
        runCatching { VoiceEntry.read(getJSONObject(index), bundled) }.getOrNull()
    }.distinctBy { it.id }

internal data class VoicePack(
    val directory: File, val engine: String, val language: String, val profile: String,
    val rate: Float, val pitch: Float, val speaker: Int, val settings: JSONObject
) {
    val locale: Locale get() = Locale.forLanguageTag(language)
    fun speaks(requested: String): Boolean = locale.language == Locale.forLanguageTag(requested).language
    fun file(name: String) = File(directory, name)

    companion object {
        fun read(directory: File): VoicePack {
            val manifest = File(directory, "voice.json")
            require(manifest.isFile && manifest.length() in 2..64_000L)
            val data = JSONObject(manifest.readText())
            require(data.getInt("schema") == 1)
            val engine = data.getString("engine")
            val language = data.getString("language")
            require(languageTag.matches(language))
            val profile = data.optString("profile").trim()
            val rate = data.optDouble("rate", 1.0).toFloat()
            val pitch = data.optDouble("pitch", 1.0).toFloat()
            val speaker = data.optInt("speaker", 0)
            require(rate in .5f..3f && pitch in 0f..3f && speaker in 0..9999)
            val result = VoicePack(directory, engine, language, profile, rate, pitch, speaker, data)
            when (engine) {
                "ruvoice" -> {
                    require(result.file("pack.json").isFile && result.file("pack.json").length() in 8..256_000L)
                    require(listOf("tts_mel.ptl", "backbone.pte", "head.ptl").all { result.file(it).length() > 1024 })
                    require(profile.length in 1..80)
                }
                "vits" -> {
                    require(result.file("model.onnx").length() in 1024..220_000_000L)
                    require(result.file("tokens.txt").length() in 2..2_000_000L)
                    require(result.file("espeak-ng-data").isDirectory || result.file("lexicon.txt").isFile)
                }
                else -> throw IOException("Unsupported voice engine")
            }
            return result
        }
    }
}

internal fun packPath(root: File, name: String): File {
    val parts = name.trimEnd('/').split('/')
    require(parts.size in 1..12 && parts.all { part ->
        part.length in 1..100 && part != "." && part != ".." &&
            part.none { it == '\\' || it == ':' || it.isISOControl() }
    })
    return File(root, parts.joinToString("/"))
}
