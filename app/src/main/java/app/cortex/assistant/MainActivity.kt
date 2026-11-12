package app.cortex.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Re-created Android host compatible with the web assets shipped in CORTEX 0.8.6.
 * Original Android Kotlin sources are not embedded in the distribution APK.
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var server: CortexServerBridge
    private lateinit var device: CortexDeviceBridge
    private lateinit var speech: CortexSpeech
    private lateinit var island: CortexIslandBridge
    private var pendingWake: String? = null
    private var pendingQuickCompose: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val background = Color.BLACK
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this).apply {
            setBackgroundColor(background)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = true  // All backend traffic uses the native HTTPS gateway.
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // Cold start from the "Cortex" wake word: hand the command to the UI once it is up.
                    pendingWake?.let { command -> pendingWake = null; view.postDelayed({ deliverWake(command) }, 1800) }
                    pendingQuickCompose?.let { text -> pendingQuickCompose = null; view.postDelayed({ deliverQuickCompose(text) }, 900) }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isAsset(request.url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.method == "GET" && isAsset(request.url)) {
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(background)
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }
        val safeAreas = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safe = insets.getInsets(safeAreas)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        speech = CortexSpeech(this) { event -> deviceEvent(event) }
        device = CortexDeviceBridge(this, speech) { event -> deviceEvent(event) }
        server = CortexServerBridge(applicationContext)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val origin = "https://$ASSET_HOST"
            WebViewCompat.addWebMessageListener(webView, "CortexServer", setOf(origin)) { _, message, source, mainFrame, reply ->
                if (mainFrame && source.toString().trimEnd('/') == origin) server.handle(message.data.orEmpty(), reply)
            }
            WebViewCompat.addWebMessageListener(webView, "CortexDevice", setOf(origin)) { _, message, source, mainFrame, _ ->
                if (mainFrame && source.toString().trimEnd('/') == origin) runOnUiThread { device.handle(message.data.orEmpty()) }
            }
        }
        island = CortexIslandBridge(this, webView, "https://$ASSET_HOST")
        pendingWake = intent?.getStringExtra(WAKE_EXTRA)
        pendingQuickCompose = intent?.getStringExtra(COMPOSE_EXTRA)
        setContentView(container)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://$ASSET_HOST/assets/web/index.html?android=1")
        }
    }

    private fun deviceEvent(event: JSONObject) {
        runOnUiThread {
            // Escape untrusted JSON as a JS string. No JS string interpolation.
            val json = JSONObject.quote(event.toString())
            webView.evaluateJavascript("window.dispatchEvent(new MessageEvent('cortex-native', {data:$json}));" +
                "if(window.CortexDevice&&typeof window.CortexDevice.onmessage==='function')window.CortexDevice.onmessage({data:$json});", null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(WAKE_EXTRA)?.let { command ->
            intent.removeExtra(WAKE_EXTRA)
            webView.postDelayed({ deliverWake(command) }, 350)
        }
        intent.getStringExtra(COMPOSE_EXTRA)?.let { text ->
            intent.removeExtra(COMPOSE_EXTRA)
            webView.postDelayed({ deliverQuickCompose(text) }, 350)
        }
    }

    private fun deliverWake(command: String) {
        deviceEvent(JSONObject().put("type", "wake").put("text", command))
    }

    private fun deliverQuickCompose(text: String) {
        deviceEvent(JSONObject().put("type", "quick-compose").put("text", text))
    }

    private fun isAsset(uri: Uri) = uri.scheme == "https" && uri.host == ASSET_HOST && uri.path?.startsWith("/assets/web/") == true

    @Deprecated("WebView forwards Back to the SPA")
    override fun onBackPressed() {
        webView.evaluateJavascript("Boolean(window.cortexBack && window.cortexBack())") { handled ->
            if (handled != "true") finish()
        }
    }

    @Deprecated("For Android document pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::device.isInitialized) device.handleActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::device.isInitialized) device.refreshPermissions()
        if (::island.isInitialized) island.refresh()
    }
    override fun onResume() {
        super.onResume()
        CortexRuntime.foreground = true
        CortexRuntime.wakeHook?.invoke()   // wake-word listener releases the microphone
        if (::device.isInitialized) device.refreshPermissions()
        if (::island.isInitialized) { island.refresh(); island.ensureIsland() }
    }
    override fun onPause() {
        CortexRuntime.foreground = false
        if (::speech.isInitialized) speech.stop()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) { webView.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        if (::speech.isInitialized) speech.close()
        if (::server.isInitialized) server.close()
        if (::webView.isInitialized) { (webView.parent as? FrameLayout)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }
    private companion object {
        const val ASSET_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
        const val WAKE_EXTRA = "cortex_wake_command"
        const val COMPOSE_EXTRA = "cortex_submit_text"
    }
}
