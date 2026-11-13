package app.cortex.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object CortexCameraCapture {
    class Request(val lens: String, val progress: (String) -> Unit, val done: (JSONObject) -> Unit) {
        val id = UUID.randomUUID().toString()
        var close: (() -> Unit)? = null
        val launchTimeout = Runnable {
            complete(this, "unavailable", "Android не открыл окно съёмки. Повторите команду из открытого CORTEX.")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var pending: Request? = null

    fun start(context: Context, lens: String, progress: (String) -> Unit, done: (JSONObject) -> Unit) {
        val request = Request(lens, progress, done)
        pending = request
        main.postDelayed(request.launchTimeout, 5_000)
        val intent = Intent(context, CortexCaptureActivity::class.java)
            .putExtra("capture", request.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            complete(request, "unavailable", "Android не открыл окно съёмки. Повторите команду из открытого CORTEX.")
        }
    }

    fun request(id: String?) = pending?.takeIf { it.id == id }?.also { main.removeCallbacks(it.launchTimeout) }
    fun active(request: Request) = pending === request

    fun complete(request: Request, status: String, text: String) {
        if (!active(request)) return
        pending = null
        main.removeCallbacks(request.launchTimeout)
        request.close?.invoke()
        request.done(JSONObject().put("status", status).put("message", text))
    }

    fun cancel() {
        val request = pending ?: return
        pending = null
        main.removeCallbacks(request.launchTimeout)
        request.close?.invoke()
    }
}

class CortexCaptureActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var request: CortexCameraCapture.Request? = null
    private var camera: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var streaming = false
    private var counting = false
    private var taking = false
    private var requestingPermission = false
    private lateinit var viewfinder: PreviewView
    private lateinit var status: TextView
    private val shutter = Runnable { takePhoto() }
    private val startupTimeout = Runnable {
        if (!streaming) complete("unavailable", "Предпросмотр камеры не запустился. Проверьте доступ к камере.")
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        if (granted) bindCamera() else complete("needs_camera", "Доступ к камере отклонён. Фото не сделано.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = CortexCameraCapture.request(intent.getStringExtra("capture")) ?: run { finish(); return }
        request = current
        current.close = { finish() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showPreview()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else {
            requestingPermission = true
            current.progress("Ожидаю доступ к камере")
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPreview() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            text = "CORTEX · Подключаю камеру"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        viewfinder = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        val stop = Button(this).apply {
            text = "Отменить съёмку"
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) }
            filterTouchesWhenObscured = true
            setOnClickListener { cancelPhoto() }
        }
        column.addView(status, LinearLayout.LayoutParams(-1, dp(44)))
        column.addView(viewfinder, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(stop, LinearLayout.LayoutParams(-1, dp(50)))
        setContentView(column)
        viewfinder.previewStreamState.observe(this) { state ->
            streaming = state == PreviewView.StreamState.STREAMING
            if (streaming) main.removeCallbacks(startupTimeout) else resetCountdown()
            prepareShutter()
        }
    }

    private fun bindCamera() {
        val current = request?.takeIf(CortexCameraCapture::active) ?: return
        main.postDelayed(startupTimeout, 12_000)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!CortexCameraCapture.active(current) || isFinishing) return@addListener
            runCatching {
                val provider = future.get()
                val lens = if (current.lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(lens)) {
                    complete("unavailable", "Запрошенная камера недоступна на этом устройстве.")
                    return@addListener
                }
                val stream = Preview.Builder().build().also { it.setSurfaceProvider(viewfinder.surfaceProvider) }
                val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                camera = provider
                preview = stream
                capture = photo
                provider.bindToLifecycle(this, lens, stream, photo)
            }.onFailure { complete("unavailable", "Не удалось включить камеру. Фото не сделано.") }
        }, mainExecutor)
    }

    private fun canTakePhoto() = streaming && !taking && hasWindowFocus() && !isFinishing &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request?.let(CortexCameraCapture::active) == true

    private fun prepareShutter() {
        if (counting || !canTakePhoto()) return
        counting = true
        status.text = "CORTEX · Съёмка через 2 секунды"
        request?.progress?.invoke("Готовлю снимок")
        main.postDelayed(shutter, 2_000)
    }

    private fun resetCountdown() {
        main.removeCallbacks(shutter)
        counting = false
    }

    private fun takePhoto() {
        counting = false
        if (!canTakePhoto()) return
        val current = request ?: return
        val imageCapture = capture ?: return
        taking = true
        status.text = "CORTEX · Сохраняю снимок"
        current.progress("Сохраняю снимок")
        val fileName = "CORTEX_${System.currentTimeMillis()}.jpg"
        val file = if (Build.VERSION.SDK_INT < 29) getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, fileName) } else null
        val destination = when {
            Build.VERSION.SDK_INT >= 29 -> ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CORTEX")
                }
            ).build()
            file != null -> ImageCapture.OutputFileOptions.Builder(file).build()
            else -> { complete("unavailable", "Папка для фотографий недоступна."); return }
        }
        viewfinder.display?.let { imageCapture.targetRotation = it.rotation }
        imageCapture.takePicture(destination, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (!CortexCameraCapture.active(current)) {
                    discard(uri, file)
                    return
                }
                val saved = if (file != null) file.length() > 0 else uri?.let {
                    runCatching { contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize > 0 } == true }.getOrDefault(false)
                } == true
                if (!saved) {
                    discard(uri, file)
                    complete("unavailable", "Камера вернула снимок, но проверить сохранение не удалось.")
                    return
                }
                val location = if (file == null) "Галерея → CORTEX" else "Папка CORTEX → Pictures"
                complete("done", "Фото сделано и сохранено. $location · $fileName")
            }

            override fun onError(exception: ImageCaptureException) {
                discard(null, file)
                complete("unavailable", "Камера не сохранила снимок. Повторите команду.")
            }
        })
    }

    private fun discard(uri: Uri?, file: File?) {
        runCatching {
            if (file != null) file.delete() else if (uri != null) contentResolver.delete(uri, null, null)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) prepareShutter() else resetCountdown()
    }

    override fun onPostResume() {
        super.onPostResume()
        prepareShutter()
    }

    override fun onStop() {
        super.onStop()
        resetCountdown()
        if (!requestingPermission && !isChangingConfigurations) cancelPhoto()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        cancelPhoto()
        preview?.let { stream -> capture?.let { camera?.unbind(stream, it) } }
        super.onDestroy()
    }

    private fun cancelPhoto() = complete("cancelled", "Съёмка отменена.")

    private fun complete(status: String, message: String) {
        request?.let { CortexCameraCapture.complete(it, status, message) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
