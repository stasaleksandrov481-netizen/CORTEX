package app.cortex.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal class IslandSignal(private val density: Float) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Path()

    fun face(canvas: Canvas, x: Float, y: Float, phase: String, time: Float, alpha: Float) {
        val color = when (phase) {
            "done" -> 0xFFE6E6E6.toInt()
            "error" -> 0xFFCFCFCF.toInt()
            "listening" -> 0xFFE5E5E5.toInt()
            "executing" -> 0xFFE1E1E1.toInt()
            else -> 0xFFF3F3F3.toInt()
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(density, density)
        ink.style = Paint.Style.FILL
        ink.color = color
        ink.alpha = (21 * alpha).roundToInt()
        canvas.drawCircle(0f, 0f, 12.6f, ink)
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = .85f
        ink.alpha = (73 * alpha).roundToInt()
        canvas.drawCircle(0f, 0f, 12.2f, ink)
        ink.strokeWidth = 1.45f
        ink.alpha = (246 * alpha).roundToInt()
        val blink = time > 0f && abs(time % 4.7f - 3.6f) < .072f
        line.rewind()
        when (phase) {
            "done" -> {
                line.moveTo(-6.5f, -2f); line.quadTo(-4.5f, -6f, -2.5f, -2f)
                line.moveTo(2.5f, -2f); line.quadTo(4.5f, -6f, 6.5f, -2f)
                line.moveTo(-4f, 3f); line.quadTo(0f, 8f, 4f, 3f)
            }
            "error" -> {
                line.moveTo(-6.3f, -4.7f); line.lineTo(-2f, -2.4f)
                line.moveTo(2f, -2.4f); line.lineTo(6.3f, -4.7f)
                line.moveTo(-3.8f, 5f); line.quadTo(0f, 2f, 3.8f, 5f)
            }
            "executing" -> {
                line.moveTo(-6f, -2.6f); line.quadTo(-4f, -4.5f, -2f, -3.2f)
                line.moveTo(2f, -2.1f); line.lineTo(5.7f, -2.1f)
                line.moveTo(-1f, 4.5f); line.quadTo(2f, 3f, 4f, 4.3f)
            }
            else -> {
                val halfEye = if (blink || phase == "stopped") .13f else 1.5f
                line.moveTo(-4.5f, -3f - halfEye); line.lineTo(-4.5f, -3f + halfEye)
                line.moveTo(4.5f, -3f - halfEye); line.lineTo(4.5f, -3f + halfEye)
                if (phase == "listening") canvas.drawOval(-2.2f, 2f, 2.2f, 6f, ink)
                else {
                    line.moveTo(-3f, 3.7f)
                    line.quadTo(0f, if (phase == "stopped") 3.7f else 6.6f, 3f, 3.7f)
                }
            }
        }
        canvas.drawPath(line, ink)
        canvas.restore()
    }

    fun wave(canvas: Canvas, x: Float, y: Float, phase: String, time: Float, level: Float, alpha: Float) {
        val amplitude = when (phase) {
            "listening" -> 1.3f + level * 8f
            "executing" -> 3.2f
            else -> 1.4f
        } * density
        ink.color = Color.WHITE
        ink.alpha = (180 * alpha).roundToInt()
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 1.32f * density
        line.rewind()
        for (i in 0..28) {
            val u = (i - 14) / 14f
            val taper = exp(-u * u * 3.6f)
            val motion = sin(u * 8.2f - time * 11f) + .36f * sin(u * 16.3f + time * 15f)
            val px = x + u * 15 * density
            val py = y + motion * taper * amplitude
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        canvas.drawPath(line, ink)
    }
}
