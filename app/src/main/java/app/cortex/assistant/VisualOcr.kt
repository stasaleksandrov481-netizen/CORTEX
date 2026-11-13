package app.cortex.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

internal data class OcrResult(val nodes: List<VisualNode> = emptyList(), val protected: Boolean = false)

@RequiresApi(30)
internal class VisualOcr(private val service: CortexScreenService) {
    private val mutex = Mutex()

    suspend fun read(frame: VisualFrame, display: Rect): OcrResult {
        val capture = screenshot(frame)
        val bitmap = capture.bitmap ?: return OcrResult(protected = capture.protected)
        return try {
            withContext(Dispatchers.Default) { mutex.withLock { recognize(bitmap, frame, display, capture.window) } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun recognize(bitmap: Bitmap, frame: VisualFrame, display: Rect, window: Boolean): OcrResult {
        val screen = Rect(frame.bounds)
        if (!screen.intersect(display)) return OcrResult()
        val source = if (window || screen == display) bitmap else {
            val left = (screen.left - display.left) * bitmap.width / display.width()
            val top = (screen.top - display.top) * bitmap.height / display.height()
            val right = (screen.right - display.left) * bitmap.width / display.width()
            val bottom = (screen.bottom - display.top) * bitmap.height / display.height()
            if (right <= left || bottom <= top) return OcrResult()
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }
        val scale = (1440f / maxOf(source.width, source.height)).coerceAtMost(1f)
        val input = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), true)
        if (source !== bitmap && source !== input) source.recycle()
        val base = File(service.filesDir, "ocr")
        val data = File(base, "tessdata").apply { mkdirs() }
        val engine = TessBaseAPI()
        return try {
            for (language in listOf("rus", "eng")) {
                val model = File(data, "$language.traineddata")
                if (model.length() == 0L) {
                    val temp = File.createTempFile(language, ".tmp", data)
                    try {
                        service.assets.open("ocr/${model.name}").use { source ->
                            temp.outputStream().use { destination -> source.copyTo(destination) }
                        }
                        check(temp.renameTo(model))
                    } finally {
                        temp.delete()
                    }
                }
            }
            if (!engine.init(base.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) return OcrResult()
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            engine.setImage(input)
            if (engine.utF8Text.isNullOrBlank()) return OcrResult()
            val iterator = engine.resultIterator ?: return OcrResult()
            val items = mutableListOf<VisualNode>()
            try {
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iterator.begin()
                do {
                    val label = VisualLabels.clean(iterator.getUTF8Text(level))
                    if (label.isBlank() || iterator.confidence(level) < 45f) continue
                    val rect = iterator.getBoundingRect(level) ?: continue
                    val bounds = Rect(screen.left + rect.left * screen.width() / input.width,
                        screen.top + rect.top * screen.height() / input.height,
                        screen.left + rect.right * screen.width() / input.width,
                        screen.top + rect.bottom * screen.height() / input.height)
                    if (!bounds.intersect(frame.bounds) || frame.tree.any {
                            Rect.intersects(it.bounds, bounds) && VisualLabels.score(it.label, label) >= 82
                        }) continue
                    items += VisualNode("ocr${items.size + 1}", emptyList(), label, "", "OCR", bounds, ocr = true)
                } while (items.size < 80 && iterator.next(level))
            } finally {
                iterator.delete()
            }
            OcrResult(items)
        } finally {
            engine.recycle()
            if (input !== bitmap) input.recycle()
        }
    }

    private suspend fun screenshot(frame: VisualFrame): Capture = suspendCancellableCoroutine { pending ->
        val useWindow = Build.VERSION.SDK_INT >= 34
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    try { if (pending.isActive) hardware?.copy(Bitmap.Config.ARGB_8888, false) else null }
                    finally { hardware?.recycle() }
                } finally {
                    buffer.close()
                }
                if (pending.isActive) pending.resume(Capture(bitmap, useWindow)) { _, result, _ -> result.bitmap?.recycle() }
                else bitmap?.recycle()
            }
            override fun onFailure(errorCode: Int) {
                if (pending.isActive) pending.resume(Capture(protected = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(frame.window, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (_: RuntimeException) {
            if (pending.isActive) pending.resume(Capture())
        }
    }

    private data class Capture(val bitmap: Bitmap? = null, val window: Boolean = false, val protected: Boolean = false)
}
