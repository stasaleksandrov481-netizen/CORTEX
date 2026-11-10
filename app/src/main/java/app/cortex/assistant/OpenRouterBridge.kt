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
import java.io.BufferedReader
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
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val work = ConcurrentHashMap<String, FutureTask<Unit>>()
    private val sockets = ConcurrentHashMap<String, HttpsURLConnection>()

    fun install(view: WebView, origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, "CortexOpenRouter", setOf(origin)) {
                _, message, source, isMainFrame, reply ->
            if (!isMainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 110_000) return@addWebMessageListener
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val id = request.optString("id")
            if (!REQUEST_ID.matches(id)) return@addWebMessageListener
            if (request.optString("operation") == "cancel") {
                cancel(id)
                return@addWebMessageListener
            }
            val job = FutureTask<Unit> {
                val response = JSONObject().put("id", id)
                try {
                    response.put("result", handle(id, request) { delta ->
                        val event = JSONObject().put("id", id).put("delta", delta)
                        main.post {
                            if (work.containsKey(id)) runCatching { reply.postMessage(event.toString()) }
                        }
                    })
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

    private fun cancel(id: String) {
        work.remove(id)?.cancel(true)
        sockets.remove(id)?.let { connection -> io.execute(connection::disconnect) }
    }

    private fun handle(id: String, request: JSONObject, emit: (String) -> Unit): JSONObject =
        when (request.optString("operation")) {
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
                val payload = request.optJSONObject("payload") ?: throw RouterError("400")
                complete(id, key, payload, emit)
            }
            else -> throw RouterError("400")
        }

    private fun checkedPayload(request: JSONObject): JSONObject {
        val turns = request.optJSONArray("messages") ?: throw RouterError("400")
        if (turns.length() !in 1..29) throw RouterError("400")
        val messages = JSONArray()
        var total = 0
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: throw RouterError("400")
            val role = turn.optString("role")
            val text = turn.optString("content")
            total += text.length
            if (role !in ROLES || text.isBlank() || total > 51_000) throw RouterError("400")
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        val models = request.optJSONArray("models") ?: throw RouterError("400")
        if (models.length() !in 1..2) throw RouterError("400")
        val routes = JSONArray()
        for (index in 0 until models.length()) {
            val model = models.optString(index)
            if (!FREE_MODEL.matches(model)) throw RouterError("400")
            routes.put(model)
        }
        return JSONObject()
            .put("models", routes)
            .put("messages", messages)
            .put("temperature", 0.42)
            .put("top_p", 0.9)
            .put("max_tokens", request.optInt("max_tokens", 1450).coerceIn(128, 2400))
            .put("reasoning", JSONObject().put("effort", "low").put("exclude", true))
            .put("stream", request.optBoolean("stream"))
    }

    private fun complete(id: String, key: String, payload: JSONObject, emit: (String) -> Unit): JSONObject {
        val request = checkedPayload(payload)
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        sockets[id] = connection
        try {
            if (!work.containsKey(id)) throw InterruptedException()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 55_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", if (request.optBoolean("stream")) "text/event-stream" else "application/json")
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw RouterError(status.toString())
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (connection.contentType?.contains("text/event-stream") == true) {
                    readStream(id, reader, emit)
                } else {
                    val data = runCatching { JSONObject(reader.readText()) }.getOrElse { throw RouterError("empty") }
                    data.optJSONObject("error")?.let { throw RouterError(it.optString("code")) }
                    content(data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"))
                }
            }.trim()
            if (text.isBlank()) throw RouterError("empty")
            return JSONObject().put("text", text.take(20_000))
        } finally {
            sockets.remove(id, connection)
            connection.disconnect()
        }
    }

    private fun readStream(id: String, reader: BufferedReader, emit: (String) -> Unit): String {
        val result = StringBuilder()
        for (line in reader.lineSequence()) {
            if (!work.containsKey(id)) throw InterruptedException()
            if (!line.startsWith("data:")) continue
            val event = line.substring(5).trim()
            if (event == "[DONE]") break
            val data = runCatching { JSONObject(event) }.getOrNull() ?: continue
            data.optJSONObject("error")?.let { throw RouterError(it.optString("code")) }
            val choice = data.optJSONArray("choices")?.optJSONObject(0)
            if (choice?.optString("finish_reason") == "error") throw RouterError("502")
            val delta = content(choice?.optJSONObject("delta")).take(20_000 - result.length)
            if (delta.isEmpty()) continue
            result.append(delta)
            emit(delta)
            if (result.length >= 20_000) break
        }
        return result.toString()
    }

    private fun content(message: JSONObject?): String =
        if (message == null || message.isNull("content")) "" else message.optString("content")

    override fun close() {
        work.keys.toList().forEach(::cancel)
        io.shutdown()
    }

    private class RouterError(val code: String) : Exception()

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        val ROLES = setOf("system", "user", "assistant")
        val FREE_MODEL = Regex("^(openrouter/free|[a-z0-9._-]+/[a-z0-9._-]+:free)$")
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
