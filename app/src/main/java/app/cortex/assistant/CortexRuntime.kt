package app.cortex.assistant

internal object CortexRuntime {
    var appVisible = false
        private set
    var islandVisibility: (() -> Unit)? = null

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

    var speaking = false
        set(value) {
            if (field == value) return
            field = value
            wakeHook?.invoke()
        }

    val audioBusy get() = recording || speaking

    fun reportWake(value: String?) {
        if (wakeStatus == value) return
        wakeStatus = value
        wakeStatusListener?.invoke(value)
    }
}
