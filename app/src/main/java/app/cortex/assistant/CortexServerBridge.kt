package app.cortex.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.UUID
import java.util.concurrent.FutureTask
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bridge to the CORTEX Supabase Edge Function. No API provider keys in the APK. */
internal class CortexServerBridge(context: Context) : AutoCloseable {
    private val state = context.getSharedPreferences("cortex-backend", Context.MODE_PRIVATE)
    private val store = SecureSession(context)
    private val executor = Executors.newFixedThreadPool(3)
    private val jobs = ConcurrentHashMap<String, FutureTask<Unit>>()
    private val main = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val installId = synchronized(installLock) {
        state.getString("install", null) ?: randomToken().also { state.edit().putString("install", it).apply() }
    }
    private var pollCode: String? = null
    private val endpoint = "https://jjcqrjztxohnifmziksy.supabase.co/functions/v1/cortex/v1/"

    fun handle(raw: String, reply: JavaScriptReplyProxy) {
        if (raw.length > 270_000) return
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = request.optString("id")
        if (!Regex("^request-[0-9]{10,16}-[0-9]{1,9}$").matches(id)) return
        if (request.optString("operation") == "cancel") {
            cancel(id)
            return
        }
        fun publish(value: JSONObject) {
            runCatching { reply.postMessage(value.put("id", id).toString()) }
        }
        enqueue(id, request, ::publish, ::publish)
    }

    fun chat(payload: JSONObject, onEvent: (JSONObject) -> Unit, done: (JSONObject) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val request = JSONObject().put("operation", "chat").put("payload", payload)
        enqueue(id, request, onEvent, done)
        return id
    }

    private fun enqueue(id: String, request: JSONObject, onEvent: (JSONObject) -> Unit, done: (JSONObject) -> Unit) {
        val task = FutureTask<Unit> {
            val outcome = JSONObject()
            try {
                outcome.put("result", perform(request, id) { event ->
                    main.post { if (jobs.containsKey(id)) onEvent(event) }
                })
            } catch (error: GatewayFailure) {
                outcome.put("error", error.code)
                if (error.retryAfter > 0) outcome.put("retry_after", error.retryAfter)
            } catch (_: SocketTimeoutException) {
                outcome.put("error", "timeout")
            } catch (_: Exception) {
                outcome.put("error", "offline")
            }
            main.post { if (jobs.remove(id) != null) done(outcome) }
        }
        if (jobs.putIfAbsent(id, task) == null) executor.execute(task)
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel(true)
        connections.remove(id)?.let { socket -> Thread(socket::disconnect, "cortex-cancel").start() }
    }

    private fun perform(request: JSONObject, id: String, onEvent: (JSONObject) -> Unit): JSONObject {
        return when (request.optString("operation")) {
            "status" -> call("GET", "status", null, id)
            "chat" -> call("POST", "chat", request.optJSONObject("payload") ?: throw GatewayFailure("invalid"), id, onEvent)
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
                     onEvent: ((JSONObject) -> Unit)? = null): JSONObject {
        val connection = (URL(endpoint + route).openConnection() as HttpURLConnection)
        connections[id] = connection
        try {
            if (!jobs.containsKey(id)) throw InterruptedException()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 92_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", if (onEvent == null) "application/json" else "text/event-stream")
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
                val error = runCatching { JSONObject(body) }.getOrNull()
                val code = error?.optString("error").orEmpty().ifBlank { if (status == 429) "rate_limit" else "server" }
                throw GatewayFailure(code, error?.optInt("retry_after") ?: 0)
            }
            return if (onEvent != null && connection.contentType.orEmpty().contains("event-stream")) {
                connection.inputStream.bufferedReader().use { sse(it, onEvent) }
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } finally {
            connections.remove(id, connection)
            connection.disconnect()
        }
    }

    private fun sse(reader: BufferedReader, onEvent: (JSONObject) -> Unit): JSONObject {
        val text = StringBuilder()
        val result = JSONObject()
        for (line in reader.lineSequence()) {
            if (Thread.currentThread().isInterrupted) throw GatewayFailure("timeout")
            if (!line.startsWith("data:")) continue
            val value = line.substring(5).trim()
            if (value == "[DONE]") break
            val event = runCatching { JSONObject(value) }.getOrNull() ?: continue
            if (event.has("error")) throw GatewayFailure(event.optString("error", "provider"), event.optInt("retry_after"))
            if (event.optString("status") == "fallback") onEvent(JSONObject().put("status", "fallback"))
            if (event.has("delta")) {
                val delta = event.optString("delta")
                if (delta.isNotEmpty() && text.length < 20_000) {
                    val part = delta.take(20_000 - text.length)
                    text.append(part)
                    onEvent(JSONObject().put("delta", part))
                }
            }
            for (key in arrayOf("tool", "tools", "tool_error")) if (event.has(key)) result.put(key, event.get(key))
        }
        if (text.isNotEmpty()) result.put("text", text.toString())
        if (result.length() == 0) throw GatewayFailure("empty")
        return result
    }
    override fun close() {
        jobs.keys.toList().forEach(::cancel)
        executor.shutdownNow()
    }
    private class GatewayFailure(val code: String, val retryAfter: Int = 0) : Exception(code)
    private companion object { val installLock = Any() }
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
