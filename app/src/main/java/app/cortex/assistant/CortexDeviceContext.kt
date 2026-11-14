package app.cortex.assistant

import android.content.Context
import android.os.Build
import org.json.JSONObject

internal object CortexDeviceContext {
    const val IDENTITY = "Ты CORTEX, персональный помощник на Android пользователя. " +
        "Ты получаешь команды из чата, островка и распознавания речи, а отвечаешь текстом. " +
        "Локальный движок выполняет доступные действия и проверяет их на устройстве. " +
        "Опирайся на реальные разрешения и результаты. Если шаг недоступен, назови ограничение и задай один конкретный вопрос."

    fun runtime(context: Context, screen: CortexScreenService?, actionsEnabled: Boolean): JSONObject =
        JSONObject().put("android_api", Build.VERSION.SDK_INT)
            .put("screen_control", actionsEnabled && screen != null && !screen.locked())
            .put("offline_ocr", actionsEnabled && screen != null && Build.VERSION.SDK_INT >= 30)
            .put("surface", if (CortexRuntime.appVisible) "chat" else "island")
            .put("task_in_progress", CortexActionState.status?.phase == "executing")
            .apply {
                val foreground = screen?.takeIf { actionsEnabled }?.activePackage()
                if (foreground == context.packageName) put("foreground", "CORTEX")
            }
}
