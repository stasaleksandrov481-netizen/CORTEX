package app.cortex.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bridge to the CORTEX Supabase Edge Function. No API provider keys in the APK. */
internal class CortexServerBridge(context: Context) : AutoCloseable {
    private val state = context.getSharedPreferences("cortex-backend", Context.MODE_PRIVATE)
    private val store = SecureSession(context)
    private val executor = Executors.newFixedThreadPool(3)
    private val jobs = ConcurrentHashMap<String, Future<*>>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val installId = state.getString("install", null) ?: randomToken().also { state.edit().putString("install", it).apply() }
    private var pollCode: String? = null
    private val endpoint = "https://jjcqrjztxohnifmziksy.supabase.co/functions/v1/cortex/v1/"

    fun handle(raw: String, reply: JavaScriptReplyProxy) {
        if (raw.length > 270_000) return
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = request.optString("id")
        if (!Regex("^request-[0-9]{10,16}-[0-9]{1,9}$").matches(id)) return
        if (request.optString("operation") == "cancel") {
            jobs.remove(id)?.cancel(true)
            connections.remove(id)?.disconnect()
            return
        }
        val task = executor.submit {
            val outcome = JSONObject().put("id", id)
            try { outcome.put("result", perform(request, id, reply)) }
            catch (error: GatewayFailure) { outcome.put("error", error.code) }
            catch (_: Exception) { outcome.put("error", "offline") }
            if (!Thread.currentThread().isInterrupted) runCatching { reply.postMessage(outcome.toString()) }
            jobs.remove(id)
        }
        jobs[id] = task
    }

    private fun perform(request: JSONObject, id: String, reply: JavaScriptReplyProxy): JSONObject {
        return when (request.optString("operation")) {
            "status" -> call("GET", "status", null, id)
            "chat" -> call("POST", "chat", request.optJSONObject("payload") ?: throw GatewayFailure("invalid"), id, reply)
            "support" -> call("POST", "support", request.optJSONObject("payload") ?: throw GatewayFailure("invalid"), id)
            "auth-start" -> call("POST", "auth/start", JSONObject(), id).also { pollCode = it.optString("poll") }
            "auth-poll" -> call("POST", "auth/poll", JSONObject().put("code", pollCode.orEmpty()), id).also {
                if (it.optString("state") == "signed_in") {
                    it.optString("token").takeIf { token -> token.isNotBlank() }?.let(store::put)
                    pollCode = null
                    it.remove("token") // Never expose the bearer token to JS.
                }
            }
            "auth-cancel" -> { pollCode = null; JSONObject().put("cancelled", true) }
            "logout" -> {
                runCatching { call("DELETE", "session", null, id) }
                store.clear()
                JSONObject().put("ok", true)
            }
            else -> throw GatewayFailure("endpoint_missing")
        }
    }

    private fun call(method: String, route: String, payload: JSONObject?, id: String,
                     reply: JavaScriptReplyProxy? = null): JSONObject {
        val connection = (URL(endpoint + route).openConnection() as HttpURLConnection)
        connections[id] = connection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 92_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", if (reply == null) "application/json" else "text/event-stream")
            connection.setRequestProperty("X-Cortex-Install", installId)
            connection.setRequestProperty("Cache-Control", "no-store")
            val token = store.get()
            if (!token.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { out -> out.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText().take(2000) }.orEmpty()
                val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                throw GatewayFailure(code.ifBlank { if (status == 429) "rate_limit" else "server" })
            }
            return if (reply != null && connection.contentType.orEmpty().contains("event-stream")) {
                connection.inputStream.bufferedReader().use { sse(it, id, reply) }
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } finally {
            connections.remove(id, connection)
            connection.disconnect()
        }
    }

    private fun sse(reader: BufferedReader, id: String, reply: JavaScriptReplyProxy): JSONObject {
        val text = StringBuilder()
        val result = JSONObject()
        for (line in reader.lineSequence()) {
            if (Thread.currentThread().isInterrupted) throw GatewayFailure("timeout")
            if (!line.startsWith("data:")) continue
            val value = line.substring(5).trim()
            if (value == "[DONE]") break
            val event = runCatching { JSONObject(value) }.getOrNull() ?: continue
            if (event.has("error")) throw GatewayFailure(event.optString("error", "provider"))
            if (event.has("delta")) {
                val delta = event.optString("delta")
                if (delta.isNotEmpty() && text.length < 20_000) {
                    val part = delta.take(20_000 - text.length)
                    text.append(part)
                    runCatching { reply.postMessage(JSONObject().put("id", id).put("delta", part).toString()) }
                }
            }
            for (key in arrayOf("tool", "tools", "tool_error")) if (event.has(key)) result.put(key, event.get(key))
        }
        if (text.isNotEmpty()) result.put("text", text.toString())
        if (result.length() == 0) throw GatewayFailure("empty")
        return result
    }
    override fun close() {
        jobs.values.forEach { it.cancel(true) }
        connections.values.forEach { it.disconnect() }
        executor.shutdownNow()
    }
    private class GatewayFailure(val code: String) : Exception(code)
    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

/** AES-GCM encrypted bearer token in the Android Keystore. */
private class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("cortex-token", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey("cortex-session-key", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("cortex-session-key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    @Synchronized fun put(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val combined = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }
    @Synchronized fun get(): String? {
        val raw = prefs.getString("session", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }
    @Synchronized fun clear() { prefs.edit().remove("session").apply() }
}
