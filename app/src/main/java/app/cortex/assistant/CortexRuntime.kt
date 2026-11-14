package app.cortex.assistant

import android.graphics.PointF

internal object CortexRuntime {
    var appVisible = false
        private set
    var islandVisibility: (() -> Unit)? = null
    var islandTouchPassThrough: ((PointF?) -> Boolean)? = null
    var wakeLevel: ((Float) -> Unit)? = null
    var wakeCommand: ((String) -> Boolean)? = null
    var shutdownListener: (() -> Unit)? = null

    fun setAppVisible(value: Boolean) {
        appVisible = value
        islandVisibility?.invoke()
    }

    var wakeHook: (() -> Unit)? = null
    var wakeStatus: String? = null
    var wakeStatusListener: ((String?) -> Unit)? = null

    var recording = false
        set(value) {
            if (field == value) return
            field = value
            wakeHook?.invoke()
        }

    fun reportWake(value: String?) {
        if (wakeStatus == value) return
        wakeStatus = value
        wakeStatusListener?.invoke(value)
    }
}
