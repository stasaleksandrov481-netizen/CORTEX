package app.cortex.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal class OpenRouterBridge(context: Context) : AutoCloseable {
    private val keys = ApiKeyStorage(context)
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val work = ConcurrentHashMap<String, FutureTask<Unit>>()

    fun install(view: WebView, origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, "CortexOpenRouter", setOf(origin)) {
                _, message, source, isMainFrame, reply ->
            if (!isMainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 45_000) return@addWebMessageListener
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val id = request.optString("id")
            if (!REQUEST_ID.matches(id)) return@addWebMessageListener
            if (request.optString("operation") == "cancel") {
                work.remove(id)?.cancel(true)
                return@addWebMessageListener
            }
            val job = FutureTask<Unit> {
                val response = JSONObject().put("id", id)
                try {
                    response.put("result", handle(request))
                } catch (error: RouterError) {
                    response.put("error", error.code)
                } catch (_: SocketTimeoutException) {
                    response.put("error", "timeout")
                } catch (_: IOException) {
                    response.put("error", "offline")
                } catch (_: Exception) {
                    response.put("error", "storage")
                }
                main.post {
                    if (work.remove(id) != null) runCatching { reply.postMessage(response.toString()) }
                }
            }
            if (work.putIfAbsent(id, job) == null) io.execute(job)
        }
    }

    private fun handle(request: JSONObject): JSONObject = when (request.optString("operation")) {
        "status" -> JSONObject().put("available", true).put("configured", keys.exists())
        "save-key" -> {
            val key = request.optString("key").trim()
            if (!API_KEY.matches(key)) throw RouterError("invalid")
            keys.store(key)
            JSONObject().put("configured", true)
        }
        "remove-key" -> {
            keys.remove()
            JSONObject().put("configured", false)
        }
        "chat" -> {
            val key = keys.read() ?: throw RouterError("missing")
            val messages = readTurns(request.optJSONArray("turns"))
            complete(key, messages, request.optBoolean("check"))
        }
        else -> throw RouterError("400")
    }

    private fun readTurns(turns: JSONArray?): JSONArray {
        if (turns == null || turns.length() !in 1..13) throw RouterError("400")
        val output = JSONArray()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: throw RouterError("400")
            val role = turn.optString("role")
            val text = turn.optString("content")
            if (role !in ROLES || text.isBlank() || text.length > 16_000) throw RouterError("400")
            output.put(JSONObject().put("role", role).put("content", text))
        }
        return output
    }

    private fun complete(key: String, messages: JSONArray, check: Boolean): JSONObject {
        val request = JSONObject()
            .put("model", "openrouter/free")
            .put("messages", messages)
            .put("max_tokens", if (check) 256 else 1400)
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 38_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw RouterError(status.toString())
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val payload = runCatching { JSONObject(body) }.getOrElse { throw RouterError("empty") }
            payload.optJSONObject("error")?.let { throw RouterError(it.optString("code")) }
            val message = payload.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val text = if (message != null && !message.isNull("content")) message.optString("content").trim() else ""
            if (text.isBlank()) throw RouterError("empty")
            return JSONObject().put("text", text.take(16_000)).put("model", payload.optString("model", "openrouter/free"))
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        work.values.forEach { it.cancel(true) }
        work.clear()
        io.shutdownNow()
    }

    private class RouterError(val code: String) : Exception()

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        val ROLES = setOf("system", "user", "assistant")
        val API_KEY = Regex("^sk-or-v1-[A-Za-z0-9_-]{24,128}$")
        val REQUEST_ID = Regex("^request-[0-9]{10,16}-[0-9]{1,8}$")
    }
}

private class ApiKeyStorage(context: Context) {
    private val prefs = context.getSharedPreferences("cortex-secure", Context.MODE_PRIVATE)

    @Synchronized
    fun exists(): Boolean = prefs.contains("api-key")

    @Synchronized
    fun store(value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val sealed = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("api-key", "$iv.$sealed").commit())
    }

    @Synchronized
    fun read(): String? {
        val packed = prefs.getString("api-key", null) ?: return null
        val (iv, sealed) = packed.split('.', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun remove() {
        check(prefs.edit().remove("api-key").commit())
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "cortex-openrouter"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
