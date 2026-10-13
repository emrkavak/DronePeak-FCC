package com.dronepeak.app

import org.junit.Assert.*
import org.junit.Test

class ShortcutGeometryTest {
    @Test fun allFourEdgesAndInterior() {
        assertEquals(ShortcutEdge.LEFT, ShortcutGeometry.dock(10f, 100f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.RIGHT, ShortcutGeometry.dock(490f, 100f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.TOP, ShortcutGeometry.dock(100f, 10f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.BOTTOM, ShortcutGeometry.dock(100f, 290f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.NONE, ShortcutGeometry.dock(250f, 150f, 500f, 300f, 24f).edge)
    }
    @Test fun cornersUseNearestEdgeWithHorizontalTiePriority() {
        assertEquals(ShortcutEdge.LEFT, ShortcutGeometry.dock(5f, 5f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.RIGHT, ShortcutGeometry.dock(495f, 5f, 500f, 300f, 24f).edge)
        assertEquals(ShortcutEdge.TOP, ShortcutGeometry.dock(8f, 3f, 500f, 300f, 24f).edge)
    }
    @Test fun positionsClampAndHandleTinyWindows() {
        assertEquals(ShortcutPosition(0f, 1f), ShortcutGeometry.clamp(-1f, 400f, 500f, 300f))
        assertEquals(ShortcutPosition(0f, 0f), ShortcutGeometry.clamp(20f, 20f, 0f, 0f))
    }
    @Test fun normalizedPositionsSurviveRotationAndRevealInward() {
        val original = ShortcutGeometry.dock(500f, 150f, 500f, 300f, 24f)
        assertEquals(1f, original.x, 0f)
        assertEquals(0.5f, original.y, 0f)
        val rotated = ShortcutGeometry.reveal(original, 300f, 500f, 32f)
        assertEquals(268f / 300f, rotated.x, 0.001f)
        assertEquals(0.5f, rotated.y, 0f)
        assertEquals(ShortcutEdge.NONE, rotated.edge)
    }
}
