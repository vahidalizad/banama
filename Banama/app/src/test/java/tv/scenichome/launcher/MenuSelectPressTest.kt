package tv.scenichome.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSelectPressTest {
    @Test
    fun menuIgnoresOpeningHoldAndAcceptsTheNextPress() {
        val press = MenuSelectPress()
        val ok = 23
        assertTrue(press.consume(ok, down = true, repeats = 3, action = "pin"))
        assertTrue(press.consume(ok, down = false, repeats = 0, action = "pin"))
        assertFalse(press.consume(ok, down = true, repeats = 0, action = "right"))
        assertFalse(press.consume(ok, down = false, repeats = 0, action = "right"))
        assertTrue(press.consume(ok, down = false, repeats = 0, action = "right"))
    }

    @Test
    fun changingFocusDuringAPressCannotSelectTheNewAction() {
        val press = MenuSelectPress()
        val ok = 23
        assertFalse(press.consume(ok, down = true, repeats = 0, action = "right"))
        assertTrue(press.consume(ok, down = false, repeats = 0, action = "uninstall"))
    }
}
