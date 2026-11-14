package app.cortex.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal class IslandSignal(private val density: Float) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Path()

    fun mark(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(density, density)
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 1.65f
        ink.alpha = (255 * alpha).roundToInt()
        line.rewind()
        line.moveTo(6.6f, -5.4f)
        line.lineTo(-.5f, -7.6f)
        line.lineTo(-6.9f, -3.8f)
        line.lineTo(-6.9f, 3.8f)
        line.lineTo(-.5f, 7.6f)
        line.lineTo(6.6f, 5.4f)
        canvas.drawPath(line, ink)
        ink.style = Paint.Style.FILL
        canvas.drawCircle(2.8f, 0f, 1.24f, ink)
        canvas.drawCircle(7.3f, 0f, .88f, ink)
        canvas.restore()
    }

    fun dot(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        ink.style = Paint.Style.FILL
        ink.alpha = (123 * alpha).roundToInt()
        canvas.drawCircle(x, y, 1.6f * density, ink)
    }

    fun check(canvas: Canvas, x: Float, y: Float, alpha: Float, progress: Float) {
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 1.45f * density
        ink.alpha = (255 * alpha).roundToInt()
        val first = minOf(1f, progress * 2.25f)
        val last = ((progress - .44f) / .56f).coerceIn(0f, 1f)
        line.rewind()
        line.moveTo(x - 5 * density, y)
        line.lineTo(x + (-5 + 3.4f * first) * density, y + 3.4f * first * density)
        if (last > 0f) line.lineTo(x + (-1.6f + 7 * last) * density, y + (3.4f - 7 * last) * density)
        canvas.drawPath(line, ink)
    }

    fun wave(canvas: Canvas, x: Float, y: Float, phase: String, time: Float, level: Float, alpha: Float) {
        val amplitude = when (phase) {
            "listening" -> 1.45f + level * 7f
            "executing" -> 2.65f
            else -> 1.05f
        } * density
        ink.alpha = (188 * alpha).roundToInt()
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 1.16f * density
        line.rewind()
        for (i in 0..28) {
            val u = (i - 14) / 14f
            val taper = exp(-u * u * 3.7f)
            val wave = sin(u * 8.4f - time * 10f + .35f * sin(time * 2.3f)) +
                .27f * sin(u * 15.2f + time * 13f)
            val px = x + u * 14 * density
            val py = y + wave * taper * amplitude
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        canvas.drawPath(line, ink)
    }
}
