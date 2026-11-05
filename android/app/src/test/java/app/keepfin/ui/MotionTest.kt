package app.keepfin.ui

import app.keepfin.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class MotionTest {
    private fun expected(response: Double) = Math.pow(2 * PI / response, 2.0).toFloat()

    @Test fun stiffnessIsTwoPiOverResponseSquared() {
        assertEquals(expected(0.24), Motion.quick.stiffness, 0.5f)
        assertEquals(expected(0.34), Motion.snappy.stiffness, 0.5f)
        assertEquals(expected(0.46), Motion.smooth.stiffness, 0.5f)
        assertEquals(expected(0.42), Motion.sheet.stiffness, 0.5f)
        assertEquals(expected(0.20), Motion.lead.stiffness, 0.5f)
        assertEquals(expected(0.44), Motion.trail.stiffness, 0.5f)
        assertEquals(expected(0.32), Motion.truth.stiffness, 0.5f)
    }

    @Test fun knownValues() {
        // (2π / 0.24)² ≈ 685.4
        assertEquals(685.4f, Motion.quick.stiffness, 0.5f)
        // (2π / 0.42)² ≈ 223.8
        assertEquals(223.8f, Motion.sheet.stiffness, 0.5f)
    }

    @Test fun tokensMatchTheDesignTable() {
        assertEquals(0.90f, Motion.quick.damping, 0f); assertEquals(0.86f, Motion.snappy.damping, 0f); assertEquals(0.94f, Motion.smooth.damping, 0f)
        assertEquals(0.90f, Motion.sheet.damping, 0f); assertEquals(0.92f, Motion.lead.damping, 0f); assertEquals(1.0f, Motion.trail.damping, 0f); assertEquals(1.0f, Motion.truth.damping, 0f)
        assertTrue(Motion.lead.stiffness > Motion.trail.stiffness)
    }
}
