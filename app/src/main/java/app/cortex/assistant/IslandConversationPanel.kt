package app.cortex.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.exp
import kotlin.math.roundToInt

internal class IslandConversationPanel(
    context: Context,
    private val sendMessage: (String) -> Unit,
    private val stopMessage: () -> Unit,
    close: () -> Unit,
    openApp: () -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val status = label(10.5f, 0xFFC6C6C6.toInt())
    private val signal = Signal()
    private val response = label(14.5f, 0xFFF0F0F0.toInt())
    private val answerScroll = ScrollView(context)
    private val header = LinearLayout(context)
    private val editor = EditText(context)
    private val entry = Entry()
    private val sendButton = Glyph("send", "Отправить", true)
    private var busy = false
    private var answer = ""
    private var motion = ValueAnimator.areAnimatorsEnabled()
    var onKeyboardFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(dp(15), dp(8), dp(13), dp(12))
        isSoundEffectsEnabled = false
        isFocusableInTouchMode = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        header.gravity = Gravity.CENTER_VERTICAL
        val identity = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wordmark = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        wordmark.addView(label(12f, Color.WHITE).apply {
            text = "CORTEX"
            letterSpacing = .18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        wordmark.addView(signal, LayoutParams(dp(67), dp(26)).apply { marginStart = dp(7) })
        identity.addView(wordmark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.maxLines = 2
        status.ellipsize = TextUtils.TruncateAt.END
        status.text = "На связи"
        status.visibility = VISIBLE
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        identity.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        header.addView(identity, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        header.addView(Glyph("open", "Открыть приложение").apply { setOnClickListener { openApp() } },
            LayoutParams(dp(44), dp(44)))
        header.addView(Glyph("close", "Свернуть островок").apply { setOnClickListener { close() } },
            LayoutParams(dp(44), dp(44)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        answerScroll.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_NEVER
            visibility = INVISIBLE
            isSoundEffectsEnabled = false
            addView(response, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        response.apply {
            setPadding(dp(4), dp(9), dp(4), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        addView(answerScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) })

        editor.apply {
            hint = "Сообщение или команда"
            isSoundEffectsEnabled = false
            contentDescription = "Сообщение Кортексу"
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(Color.WHITE)
            highlightColor = 0x66777777
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setSize(dp(2), dp(18))
                }
            }
            textSize = 14f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minHeight = dp(44)
            maxLines = 2
            filters = arrayOf(InputFilter.LengthFilter(600))
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setOnFocusChangeListener { _, focused ->
                entry.focused = focused
                entry.invalidate()
                onKeyboardFocus?.invoke(focused)
            }
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed
                if (action != EditorInfo.IME_ACTION_SEND && !enter) false
                else {
                    if (!busy) submit()
                    true
                }
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP && !hasFocus()) {
                    onKeyboardFocus?.invoke(true)
                    requestFocus()
                    post { keyboard()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refreshSend()
            })
        }
        entry.addView(editor, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        entry.addView(sendButton, LayoutParams(dp(44), dp(44)))
        sendButton.setOnClickListener { if (busy) stopMessage() else submit() }
        addView(entry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshSend()
    }

    fun status(phase: String, label: String) {
        if (signal.phase != phase) {
            signal.phase = phase
            signal.invalidate()
        }
        val message = label.ifBlank { "На связи" }
        if (status.text.toString() == message) return
        status.text = message
    }

    fun animateSignal(animate: Boolean) {
        if (motion == animate) return
        motion = animate
        signal.invalidate()
    }

    fun audioLevel(level: Float) {
        signal.level = level
        signal.levelAt = SystemClock.uptimeMillis()
        signal.invalidate()
    }

    fun busy(active: Boolean) {
        if (busy == active) return
        busy = active
        sendButton.kind = if (active) "stop" else "send"
        sendButton.contentDescription = if (active) "Остановить задачу" else "Отправить"
        sendButton.invalidate()
        refreshSend()
    }

    fun answer(value: String) {
        if (answer == value) return
        val wasEmpty = answer.isBlank()
        answer = value
        val hasAnswer = value.isNotBlank()
        response.text = value
        answerScroll.visibility = if (hasAnswer) VISIBLE else INVISIBLE
        if (wasEmpty && hasAnswer) {
            answerScroll.scrollTo(0, 0)
            if (motion) {
                response.alpha = 0f
                response.translationY = dp(8).toFloat()
                response.scaleX = .986f
                response.animate().alpha(1f).translationY(0f).scaleX(1f).setDuration(330)
                    .setInterpolator(android.view.animation.PathInterpolator(.18f, 1f, .32f, 1f)).start()
            }
        }
    }

    fun reveal(progress: Float) {
        val density = resources.displayMetrics.density
        header.translationY = (1f - progress) * 5 * density
        answerScroll.alpha = ((progress - .12f) / .88f).coerceIn(0f, 1f)
        answerScroll.translationY = (1f - progress) * 8 * density
        entry.alpha = ((progress - .28f) / .72f).coerceIn(0f, 1f)
        entry.translationY = (1f - progress) * 9 * density
    }

    fun hideKeyboard() {
        if (!editor.hasFocus()) return
        keyboard()?.hideSoftInputFromWindow(windowToken, 0)
        requestFocus()
        editor.clearFocus()
        onKeyboardFocus?.invoke(false)
    }

    fun draft() = editor.text.toString()
    fun draft(value: String) { editor.setText(value); editor.setSelection(editor.length()) }

    private fun submit() {
        val value = draft().trim()
        if (value.isEmpty()) return
        editor.text.clear()
        hideKeyboard()
        sendMessage(value)
    }

    private fun refreshSend() {
        val enabled = busy || editor.text.isNotBlank()
        if (sendButton.isEnabled != enabled) {
            sendButton.isEnabled = enabled
            sendButton.invalidate()
        }
    }

    private fun keyboard() = context.getSystemService(InputMethodManager::class.java)
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private inner class Signal : View(context) {
        private val drawing = IslandSignal(density)
        var phase = "idle"
        var levelAt = 0L
        var level = 0f

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val moving = motion && isShown && windowVisibility == VISIBLE &&
                (phase == "listening" || phase == "executing")
            val now = SystemClock.uptimeMillis()
            val time = if (moving) (now % 720_000L) / 1000f else 0f
            drawing.mark(canvas, 13 * density, height / 2f, 1f)
            drawing.wave(canvas, 47 * density, height / 2f, phase, time,
                level * exp((levelAt - now) / 240f), 1f)
            if (moving) postInvalidateDelayed(33)
        }
    }

    private inner class Entry : LinearLayout(context) {
        var focused = false
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(2), dp(2), dp(2))
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            val inset = .65f * density
            val radius = (height / 2f).coerceAtMost(29 * density)
            ink.style = Paint.Style.FILL
            ink.color = if (focused) 0xFF101010.toInt() else Color.BLACK
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, ink)
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = density * .8f
            ink.color = if (focused) 0xFFA0A0A0.toInt() else 0xFF333333.toInt()
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, ink)
        }
    }

    private inner class Glyph(
        var kind: String,
        label: String,
        private val prominent: Boolean = false
    ) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val symbol = Path()

        init {
            isClickable = true
            isSoundEffectsEnabled = false
            isFocusable = true
            contentDescription = label
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isEnabled && motion && ValueAnimator.areAnimatorsEnabled()) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().scaleX(.85f).scaleY(.76f).setInterpolator(null).setDuration(95).start()
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                        animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.85f)).setDuration(270).start()
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(density, density)
            if (prominent) {
                pen.style = Paint.Style.FILL
                pen.color = if (isEnabled) Color.WHITE else 0xFF303030.toInt()
                canvas.drawCircle(0f, 0f, 16.3f, pen)
                pen.color = if (isEnabled) 0xFF080808.toInt() else 0xFF8C8C8C.toInt()
            } else pen.color = if (isPressed) Color.WHITE else 0xFFC8C8C8.toInt()
            pen.style = Paint.Style.STROKE
            symbol.rewind()
            when (kind) {
                "close" -> {
                    symbol.moveTo(-4.6f, 1.8f)
                    symbol.quadTo(-2.1f, -1.1f, 0f, -2.5f)
                    symbol.quadTo(2.1f, -1.1f, 4.6f, 1.8f)
                }
                "open" -> {
                    symbol.moveTo(-4.4f, 4.4f)
                    symbol.lineTo(4.4f, -4.4f)
                    symbol.moveTo(-1.1f, -4.4f)
                    symbol.lineTo(4.4f, -4.4f)
                    symbol.lineTo(4.4f, 1.1f)
                }
                "stop" -> {
                    pen.style = Paint.Style.FILL
                    symbol.addRoundRect(-3.7f, -3.7f, 3.7f, 3.7f, 1.5f, 1.5f, Path.Direction.CW)
                }
                else -> {
                    symbol.moveTo(0f, 5.7f)
                    symbol.lineTo(0f, -5.7f)
                    symbol.moveTo(-4.5f, -1.1f)
                    symbol.quadTo(-2.3f, -3.8f, 0f, -5.7f)
                    symbol.quadTo(2.3f, -3.8f, 4.5f, -1.1f)
                }
            }
            canvas.drawPath(symbol, pen)
            canvas.restore()
        }
    }
}
