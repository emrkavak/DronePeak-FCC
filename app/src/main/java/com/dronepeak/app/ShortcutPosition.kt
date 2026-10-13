package com.dronepeak.app

internal enum class ShortcutEdge { NONE, LEFT, RIGHT, TOP, BOTTOM }
internal data class ShortcutPosition(val x: Float, val y: Float, val edge: ShortcutEdge = ShortcutEdge.NONE)

/** Normalized top-left coordinates survive rotation and changes in vendor-configured density. */
internal object ShortcutGeometry {
    fun clamp(x: Float, y: Float, maxX: Float, maxY: Float): ShortcutPosition =
        ShortcutPosition(if (maxX > 0) x.coerceIn(0f, maxX) / maxX else 0f,
            if (maxY > 0) y.coerceIn(0f, maxY) / maxY else 0f)

    fun dock(x: Float, y: Float, maxX: Float, maxY: Float, threshold: Float): ShortcutPosition {
        val position = clamp(x, y, maxX, maxY)
        val px = position.x * maxX.coerceAtLeast(0f)
        val py = position.y * maxY.coerceAtLeast(0f)
        // Stable tie order gives left/right precedence at corners.
        val distances = listOf(ShortcutEdge.LEFT to px, ShortcutEdge.RIGHT to (maxX - px),
            ShortcutEdge.TOP to py, ShortcutEdge.BOTTOM to (maxY - py))
        val nearest = distances.minBy { it.second }
        return if (nearest.second <= threshold) when (nearest.first) {
            ShortcutEdge.LEFT -> position.copy(x = 0f, edge = nearest.first)
            ShortcutEdge.RIGHT -> position.copy(x = 1f, edge = nearest.first)
            ShortcutEdge.TOP -> position.copy(y = 0f, edge = nearest.first)
            else -> position.copy(y = 1f, edge = nearest.first)
        } else position
    }

    fun reveal(position: ShortcutPosition, maxX: Float, maxY: Float, inset: Float): ShortcutPosition {
        val x = when (position.edge) {
            ShortcutEdge.LEFT -> inset
            ShortcutEdge.RIGHT -> maxX - inset
            else -> position.x * maxX
        }
        val y = when (position.edge) {
            ShortcutEdge.TOP -> inset
            ShortcutEdge.BOTTOM -> maxY - inset
            else -> position.y * maxY
        }
        return clamp(x, y, maxX, maxY)
    }
}
