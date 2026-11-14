package app.cortex.assistant

import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class IslandCutout(val edge: Int, val bounds: Rect)

internal data class IslandScreen(val bounds: Rect, val bars: Rect, val cutouts: List<IslandCutout>) {
    fun safeArea(density: Float): RectF {
        val inset = 8 * density
        return RectF(
            bounds.left + maxOf(bars.left, depth(Gravity.LEFT) { it.right - bounds.left }) + inset,
            bounds.top + maxOf(bars.top, depth(Gravity.TOP) { it.bottom - bounds.top }) + 3 * density,
            bounds.right - maxOf(bars.right, depth(Gravity.RIGHT) { bounds.right - it.left }) - inset,
            bounds.bottom - maxOf(bars.bottom, depth(Gravity.BOTTOM) { bounds.bottom - it.top }) - inset
        )
    }

    private fun depth(edge: Int, size: (Rect) -> Int) =
        cutouts.filter { it.edge == edge }.maxOfOrNull { size(it.bounds) } ?: 0
}

internal class IslandPlacement(val screen: IslandScreen, density: Float) {
    val compact: RectF
    val panel: RectF
    val anchorX: Float
    val anchorY: Float
    private val closedWindow: Rect
    private val openWindow: Rect

    init {
        val display = screen.bounds
        val safe = screen.safeArea(density)
        val center = display.exactCenterX()
        val camera = screen.cutouts.filter { it.edge == Gravity.TOP }.minByOrNull {
            abs(it.bounds.exactCenterX() - center)
        } ?: screen.cutouts.firstOrNull { it.edge == Gravity.LEFT || it.edge == Gravity.RIGHT }
            ?: screen.cutouts.firstOrNull { it.edge == Gravity.BOTTOM }

        val panelWidth = minOf(344 * density, safe.width()).coerceAtLeast(1f)
        val panelHeight = minOf(240 * density, safe.height() * .57f).coerceAtLeast(1f)
        val width = minOf(108 * density, panelWidth)
        val height = minOf(40 * density, panelHeight)
        val desiredX = when (camera?.edge) {
            Gravity.LEFT -> safe.left
            Gravity.RIGHT -> safe.right - width
            else -> (camera?.bounds?.exactCenterX() ?: center) - width / 2
        }
        val desiredY = when (camera?.edge) {
            Gravity.LEFT, Gravity.RIGHT -> camera.bounds.exactCenterY() - height / 2
            Gravity.BOTTOM -> safe.bottom - height
            else -> safe.top
        }
        val left = desiredX.coerceIn(safe.left, maxOf(safe.left, safe.right - width))
        val top = desiredY.coerceIn(safe.top, maxOf(safe.top, safe.bottom - height))
        compact = RectF(left, top, left + width, top + height)

        val panelLeft = (compact.centerX() - panelWidth / 2).coerceIn(safe.left, maxOf(safe.left, safe.right - panelWidth))
        val panelTop = when (camera?.edge) {
            Gravity.BOTTOM -> compact.bottom - panelHeight
            Gravity.LEFT, Gravity.RIGHT -> compact.centerY() - panelHeight / 2
            else -> compact.top
        }.coerceIn(safe.top, maxOf(safe.top, safe.bottom - panelHeight))
        panel = RectF(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)

        anchorX = when {
            compact.left <= safe.left + density -> compact.left
            compact.right >= safe.right - density -> compact.right
            else -> compact.centerX()
        }
        anchorY = when (camera?.edge) {
            Gravity.BOTTOM -> compact.bottom
            Gravity.LEFT, Gravity.RIGHT -> compact.centerY()
            else -> compact.top
        }
        closedWindow = frame(compact, density)
        openWindow = Rect(closedWindow).apply { union(frame(panel, density)) }
    }

    fun window(open: Boolean) = Rect(if (open) openWindow else closedWindow)

    private fun frame(rect: RectF, density: Float) = Rect(
        maxOf(screen.bounds.left, floor(rect.left - density).toInt()),
        maxOf(screen.bounds.top, floor(rect.top - density).toInt()),
        minOf(screen.bounds.right, ceil(rect.right + density).toInt()),
        minOf(screen.bounds.bottom, ceil(rect.bottom + density).toInt())
    )
}
