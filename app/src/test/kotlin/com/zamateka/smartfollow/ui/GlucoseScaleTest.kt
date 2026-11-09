package com.zamateka.smartfollow.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlucoseScaleTest {

    @Test
    fun endsAndAnchors() {
        assertEquals(0f, GlucoseScale.fraction(400f))
        assertEquals(1f, GlucoseScale.fraction(40f))
        assertEquals(0.62f, GlucoseScale.fraction(100f), 1e-4f)
        assertEquals(0f, GlucoseScale.fraction(500f), "clamped")
    }

    @Test
    fun lowsGetMoreRoomThanHighs() {
        val lowSpan = GlucoseScale.fraction(55f) - GlucoseScale.fraction(70f)
        val highSpan = GlucoseScale.fraction(250f) - GlucoseScale.fraction(265f)
        assertTrue(lowSpan > 2 * highSpan)
    }

    @Test
    fun mgDlIsTheInverse() {
        listOf(40, 55, 70, 100, 180, 250, 300, 400).forEach {
            assertEquals(it.toFloat(), GlucoseScale.mgDl(GlucoseScale.fraction(it.toFloat())), 0.01f)
        }
    }

    @Test
    fun rowsArePushedApartWithinTheRuler() {
        assertEquals(listOf(0f, 50f, 100f), spreadRows(listOf(10f, 20f, 30f), rowHeight = 50f, total = 500f))
        assertEquals(listOf(400f, 450f), spreadRows(listOf(480f, 490f), rowHeight = 50f, total = 500f))
        assertEquals(listOf(75f, 275f), spreadRows(listOf(100f, 300f), rowHeight = 50f, total = 500f), "far apart rows stay centred")
    }
}
