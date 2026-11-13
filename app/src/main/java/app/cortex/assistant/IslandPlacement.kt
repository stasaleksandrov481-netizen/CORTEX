package app.cortex.assistant

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class IslandCutout(val edge: Int, val bounds: Rect, val contour: Path? = null)

internal data class IslandScreen(val bounds: Rect, val bars: Rect, val cutouts: List<IslandCutout>) {
    // Each component of bars is the distance from its corresponding display edge.
    fun safeArea(density: Float): RectF {
        val side = 9 * density
        return RectF(
            bounds.left + maxOf(bars.left, depth(Gravity.LEFT) { it.right - bounds.left }) + side,
            bounds.top + maxOf(bars.top, depth(Gravity.TOP) { it.bottom - bounds.top }) + 2 * density,
            bounds.right - maxOf(bars.right, depth(Gravity.RIGHT) { bounds.right - it.left }) - side,
            bounds.bottom - maxOf(bars.bottom, depth(Gravity.BOTTOM) { bounds.bottom - it.top }) - side
        )
    }

    private fun depth(edge: Int, size: (Rect) -> Int) =
        cutouts.filter { it.edge == edge }.maxOfOrNull { size(it.bounds) } ?: 0
}

internal data class IslandAnchor(val cutout: IslandCutout, val collar: RectF, val x: Float, val y: Float)

internal class IslandPlacement(
    val screen: IslandScreen,
    density: Float,
    hasStatus: Boolean,
    panelWidth: Int,
    panelHeight: Int
) {
    val compact: RectF
    val panel: RectF
    val anchor: IslandAnchor?
    private val collapsedFrame: Rect
    private val expandedFrame: Rect

    init {
        val bounds = screen.bounds
        val safe = screen.safeArea(density)
        val middle = bounds.exactCenterX()
        val camera = screen.cutouts.filter { it.edge == Gravity.TOP }.minByOrNull {
            abs(it.bounds.exactCenterX() - middle)
        } ?: screen.cutouts.firstOrNull { it.edge == Gravity.LEFT || it.edge == Gravity.RIGHT }
            ?: screen.cutouts.firstOrNull { it.edge == Gravity.BOTTOM }
        val cutout = camera?.bounds
        val edge = camera?.edge
        val offCenter = cutout != null && abs(cutout.exactCenterX() - middle) > bounds.width() * .18f
        val bodyWidth = minOf((if (hasStatus) 198 else 99) * density, safe.width()).coerceAtLeast(1f)
        val bodyHeight = minOf(32 * density, safe.height()).coerceAtLeast(1f)
        val gap = 5 * density
        val left = when {
            cutout == null -> middle - bodyWidth / 2
            edge == Gravity.LEFT -> cutout.right + gap
            edge == Gravity.RIGHT -> cutout.left - gap - bodyWidth
            edge == Gravity.TOP && offCenter -> if (cutout.exactCenterX() < middle) {
                cutout.right - 5 * density
            } else {
                cutout.left + 5 * density - bodyWidth
            }
            else -> cutout.exactCenterX() - bodyWidth / 2
        }.coerceIn(safe.left, maxOf(safe.left, safe.right - bodyWidth))
        val top = when (edge) {
            Gravity.LEFT, Gravity.RIGHT -> cutout!!.exactCenterY() - bodyHeight / 2
            Gravity.BOTTOM -> safe.bottom - bodyHeight
            else -> safe.top + if (camera == null) 4 * density else 0f
        }.coerceIn(safe.top, maxOf(safe.top, safe.bottom - bodyHeight))
        compact = RectF(left, top, left + bodyWidth, top + bodyHeight)
        anchor = camera?.let { anchorFor(it, bounds, offCenter, density) }

        val panelX = (compact.centerX() - panelWidth / 2f)
            .coerceIn(safe.left, maxOf(safe.left, safe.right - panelWidth))
        val aboveKeyboard = bounds.top + bounds.height() * .58f - panelHeight
        val panelY = minOf(compact.top, aboveKeyboard)
            .coerceIn(safe.top, maxOf(safe.top, safe.bottom - panelHeight))
        panel = RectF(panelX, panelY, panelX + panelWidth, panelY + panelHeight)

        collapsedFrame = frame(bounds, compact, 10 * density, 76 * density)
        expandedFrame = frame(bounds, panel, 16 * density, 76 * density)
    }

    private fun anchorFor(camera: IslandCutout, display: Rect, offCenter: Boolean, density: Float): IslandAnchor {
        val rect = camera.bounds
        val rim = RectF(rect).apply { inset(-7.5f * density, -6 * density) }
        val inset = 3.5f * density
        return when (camera.edge) {
            Gravity.LEFT -> IslandAnchor(camera, rim, rect.right + inset, rect.exactCenterY())
            Gravity.RIGHT -> IslandAnchor(camera, rim, rect.left - inset, rect.exactCenterY())
            Gravity.BOTTOM -> IslandAnchor(camera, rim, rect.exactCenterX(), rect.top - inset)
            else -> {
                val towardInside = if (rect.exactCenterX() < display.exactCenterX()) rect.right - inset else rect.left + inset
                IslandAnchor(camera, rim, if (offCenter) towardInside else rect.exactCenterX(), rect.bottom + inset)
            }
        }
    }

    private fun frame(display: Rect, body: RectF, padding: Float, range: Float): Rect {
        val extent = RectF(body)
        if (anchor != null && near(anchor, body, range)) extent.union(anchor.collar)
        extent.inset(-padding, -padding)
        return Rect(
            maxOf(display.left, floor(extent.left).toInt()), maxOf(display.top, floor(extent.top).toInt()),
            minOf(display.right, ceil(extent.right).toInt()), minOf(display.bottom, ceil(extent.bottom).toInt())
        )
    }

    fun frame(open: Boolean, moving: Boolean) = when {
        moving -> Rect(collapsedFrame).apply { union(expandedFrame) }
        open -> expandedFrame
        else -> collapsedFrame
    }

    companion object {
        fun near(anchor: IslandAnchor, body: RectF, distance: Float): Boolean =
            anchor.x in (body.left - distance)..(body.right + distance) &&
                anchor.y in (body.top - distance)..(body.bottom + distance)
    }
}
