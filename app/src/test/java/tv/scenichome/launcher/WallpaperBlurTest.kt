package tv.scenichome.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperBlurTest {
    @Test
    fun glassTintKeepsDarkPhotoColorAndDampensBrightPhotoColor() {
        assertEquals(0.12f, glassTint(0.05f), 0.001f)
        assertEquals(0.56f, glassTint(1f), 0.001f)
        assertTrue(glassTint(0.5f) in 0.12f..0.56f)
    }

    @Test
    fun flatColorAndAlphaStayConstantAtTheEdges() {
        val pixels = IntArray(12) { 0xA0224466.toInt() }
        assertArrayEquals(pixels, blurArgb(pixels, 4, 3, 2))
    }

    @Test
    fun sharpDetailsSpreadInBothDirectionsWithoutChangingTheSource() {
        val side = 9
        val center = 4 * side + 4
        val pixels = IntArray(side * side) { 0xFF000000.toInt() }
        pixels[center] = 0xFFFFFFFF.toInt()

        val blurred = blurArgb(pixels, side, side, 1)
        val peak = blurred[center] and 255
        assertTrue(peak in 1..254)
        assertTrue(blurred[center + 1] and 255 in 1 until peak)
        assertEquals(blurred[center + 1], blurred[center + side])
        assertEquals(0, blurred.first() and 255)
        assertEquals(0xFFFFFFFF.toInt(), pixels[center])
    }
}
