package app.cortex.assistant

import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Re-created Android host compatible with the web assets shipped in CORTEX 0.8.6.
 * Original Android Kotlin sources are not embedded in the distribution APK.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var server: CortexServerBridge
    private lateinit var device: CortexDeviceBridge
    private lateinit var speech: CortexSpeech
    private lateinit var island: CortexIslandBridge
    private lateinit var container: FrameLayout
    private var rendererFailed = false
    private var nativeReady = false
    private val wakeCommand: (String) -> Boolean = { command ->
        val available = !rendererFailed && nativeReady && CortexRuntime.appVisible
        if (available) deviceEvent(JSONObject().put("type", "wake-command").put("text", command))
        available
    }
    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val uri = result.resultUri
        if (result.resultCode == AuthTabIntent.RESULT_OK && uri != null) authReturned(uri)
        else if (::webView.isInitialized) deviceEvent(JSONObject().put("type", "auth-tab-closed"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (cleanupStarted.compareAndSet(false, true)) {
            val context = applicationContext
            Thread({
                File(context.filesDir, "voices").deleteRecursively()
                context.deleteSharedPreferences("cortex-voices")
            }, "CortexStorageCleanup").start()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.isSoundEffectsEnabled = false
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val safeAreas = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safe = insets.getInsets(safeAreas)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)
        createWebView(savedInstanceState)
    }

    private fun createWebView(savedInstanceState: Bundle? = null) {
        rendererFailed = false
        nativeReady = false
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isSoundEffectsEnabled = false
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isAsset(request.url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.method == "GET" && isAsset(request.url)) {
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (!rendererFailed && view === webView) showRendererError()
                    return true
                }
            }
        }
        container.removeAllViews()
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
        speech = CortexSpeech(this) { event -> deviceEvent(event) }
        device = CortexDeviceBridge(this, speech, ::openGoogleLogin, ::deviceEvent)
        server = CortexServerBridge(applicationContext)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val origin = "https://$ASSET_HOST"
            WebViewCompat.addWebMessageListener(webView, "CortexServer", setOf(origin)) { _, message, source, mainFrame, reply ->
                if (mainFrame && source.toString().trimEnd('/') == origin) server.handle(message.data.orEmpty(), reply)
            }
            WebViewCompat.addWebMessageListener(webView, "CortexDevice", setOf(origin)) { _, message, source, mainFrame, _ ->
                if (mainFrame && source.toString().trimEnd('/') == origin) runOnUiThread {
                    val data = message.data.orEmpty()
                    if (!nativeReady && runCatching { JSONObject(data).optString("operation") }.getOrNull() == "permissions") {
                        nativeReady = true
                    }
                    device.handle(data)
                }
            }
        }
        CortexRuntime.wakeCommand = wakeCommand
        island = CortexIslandBridge(this, webView, "https://$ASSET_HOST")
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://$ASSET_HOST/assets/web/index.html?android=1")
        }
    }

    private fun showRendererError() {
        rendererFailed = true
        closeWebView()
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = "Android System WebView завершился. Попробуйте снова или обновите Android System WebView."
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            addView(Button(this@MainActivity).apply {
                text = "Повторить"
                isSoundEffectsEnabled = false
                setOnClickListener {
                    createWebView()
                    notifyVisibility(true)
                    island.refresh()
                    island.ensureIsland()
                }
            })
        }
        container.addView(message, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openGoogleLogin(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        require(uri.scheme == "https" && uri.host == "jjcqrjztxohnifmziksy.supabase.co" &&
            uri.path == "/auth/v1/authorize" && uri.getQueryParameter("provider") == "google")
        try {
            val browser = CustomTabsClient.getPackageName(this, null)
            if (browser != null && CustomTabsClient.isAuthTabSupported(this, browser)) {
                AuthTabIntent.Builder().build().also { it.intent.setPackage(browser) }
                    .launch(authTab, uri, BuildConfig.APPLICATION_ID)
            } else {
                CustomTabsIntent.Builder().setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK).build()
                    .also { if (browser != null) it.intent.setPackage(browser) }.launchUrl(this, uri)
            }
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
        true
    }.getOrDefault(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::authReturned)
    }

    private fun authReturned(uri: Uri) {
        if (uri.scheme == BuildConfig.APPLICATION_ID && uri.host == "oauth" && uri.path == "/return" && ::webView.isInitialized) {
            deviceEvent(JSONObject().put("type", "auth-return"))
        }
    }

    private fun deviceEvent(event: JSONObject) {
        runOnUiThread {
            if (rendererFailed || !::webView.isInitialized) return@runOnUiThread
            // Escape untrusted JSON as a JS string. No JS string interpolation.
            val json = JSONObject.quote(event.toString())
            webView.evaluateJavascript("window.dispatchEvent(new MessageEvent('cortex-native', {data:$json}));" +
                "if(window.CortexDevice&&typeof window.CortexDevice.onmessage==='function')window.CortexDevice.onmessage({data:$json});", null)
        }
    }

    private fun isAsset(uri: Uri) = uri.scheme == "https" && uri.host == ASSET_HOST && uri.path?.startsWith("/assets/web/") == true

    @Deprecated("WebView forwards Back to the SPA")
    override fun onBackPressed() {
        if (rendererFailed) { finish(); return }
        webView.evaluateJavascript("Boolean(window.cortexBack && window.cortexBack())") { handled ->
            if (handled != "true") finish()
        }
    }

    @Deprecated("For Android document pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!rendererFailed && ::device.isInitialized) device.handleActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!rendererFailed && ::device.isInitialized) device.refreshPermissions()
        if (!rendererFailed && ::island.isInitialized) island.refresh()
    }
    override fun onResume() {
        super.onResume()
        CortexRuntime.setAppVisible(true)
        notifyVisibility(true)
        if (!rendererFailed && ::device.isInitialized) device.refreshPermissions()
        if (!rendererFailed && ::island.isInitialized) { island.refresh(); island.ensureIsland() }
    }
    override fun onPause() {
        CortexRuntime.setAppVisible(false)
        notifyVisibility(false)
        if (!rendererFailed && ::speech.isInitialized) speech.stop()
        super.onPause()
    }
    private fun notifyVisibility(visible: Boolean) {
        if (!rendererFailed && ::webView.isInitialized) webView.evaluateJavascript(
            "window.cortexAppVisible=$visible;window.dispatchEvent(new Event('cortex-app-visibility'));", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!rendererFailed) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (!rendererFailed) closeWebView()
        super.onDestroy()
    }

    private fun closeWebView() {
        if (CortexRuntime.wakeCommand === wakeCommand) CortexRuntime.wakeCommand = null
        nativeReady = false
        if (::speech.isInitialized) speech.close()
        if (::device.isInitialized) device.close()
        if (::server.isInitialized) server.close()
        if (::island.isInitialized) island.close()
        if (::webView.isInitialized) { (webView.parent as? FrameLayout)?.removeView(webView); webView.destroy() }
    }
    private companion object {
        private val cleanupStarted = AtomicBoolean()
        const val ASSET_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
    }
}
