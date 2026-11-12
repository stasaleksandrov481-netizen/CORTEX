package app.cortex.assistant

/** Tiny process-wide state shared by the activity and the background services. */
object CortexRuntime {
    /** true while MainActivity is on screen (the in-app recognizer owns the microphone). */
    @Volatile var foreground = false
    /** Set by CortexWakeService so the activity can make it release the microphone at once. */
    @Volatile var wakeHook: (() -> Unit)? = null
}
