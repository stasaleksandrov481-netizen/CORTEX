package app.cortex.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var router: OpenRouterBridge
    private var voiceReply: JavaScriptReplyProxy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val background = Color.BLACK
        webView = WebView(this).apply {
            setBackgroundColor(background)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isLocalAsset(request.url)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.method == "GET" && isLocalAsset(request.url)) {
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                    }
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 404, "Not Found", emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(background)
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }
        val safeAreas = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safe = insets.getInsets(safeAreas)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        router = OpenRouterBridge(applicationContext)
        router.install(webView, "https://$ASSET_HOST")
        installVoiceInput()
        setContentView(container)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://$ASSET_HOST/assets/web/index.html?android=1")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::goBack
            )
        }
    }


    private fun installVoiceInput() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origin = "https://$ASSET_HOST"
        WebViewCompat.addWebMessageListener(webView, "CortexDevice", setOf(origin)) {
                _, message, source, mainFrame, reply ->
            if (!mainFrame || source.toString().trimEnd('/') != origin || message.data != "dictate") return@addWebMessageListener
            if (voiceReply != null) return@addWebMessageListener
            voiceReply = reply
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, SPEECH_REQUEST)
            } catch (_: ActivityNotFoundException) {
                finishVoice(JSONObject().put("error", "На устройстве недоступно распознавание речи."))
            } catch (_: SecurityException) {
                finishVoice(JSONObject().put("error", "Система не разрешила запуск распознавания речи."))
            }
        }
    }

    @Deprecated("Used to receive the result of the device speech recognizer.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST) return
        val recognized = if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.take(6000)
        } else null
        finishVoice(JSONObject().put("text", recognized.orEmpty()))
    }

    private fun finishVoice(result: JSONObject) {
        val reply = voiceReply
        voiceReply = null
        runCatching { reply?.postMessage(result.toString()) }
    }

    private fun isLocalAsset(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == ASSET_HOST && uri.path?.startsWith("/assets/web/") == true

    private fun goBack() {
        webView.evaluateJavascript("Boolean(window.cortexBack && window.cortexBack())") { handled ->
            if (handled != "true") finish()
        }
    }

    @Deprecated("Back gesture dispatch uses OnBackInvokedDispatcher on Android 13 and later.")
    override fun onBackPressed() = goBack()

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        (webView.parent as? FrameLayout)?.removeView(webView)
        router.close()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val SPEECH_REQUEST = 301
        const val ASSET_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
    }
}
