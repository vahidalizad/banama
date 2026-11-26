package tv.scenichome.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPolicyTest {
    @Test
    fun homeStatusUsesTheActualDestinationEvenWhenTheRoleIsHeld() {
        assertEquals("BananaLauncher is selected. This TV sends Home to Google TV.",
            homeRouteDescription(opensHere = false, roleHeld = true, destination = "Google TV"))
        assertEquals("Choose a default Home app in TV settings.",
            homeRouteDescription(opensHere = false, roleHeld = true, destination = null))
        assertEquals("Android opens BananaLauncher for Home.",
            homeRouteDescription(opensHere = true, roleHeld = false, destination = "BananaLauncher"))
    }

    @Test
    fun dockAndFocusRecoverWhenAnAppDisappears() {
        val dock = dockApps(
            listOf("a", "b", "c", "d", "e", "f", "g"),
            listOf("gone", "b"),
            listOf("b", "d"),
        )
        assertEquals(listOf("b", "d", "a", "c"), dock)
        assertEquals("b", focusAfterRefresh("gone", dock))
        assertEquals("d", focusAfterRefresh("d", dock))
        assertNull(focusAfterRefresh("gone", emptyList()))
    }

    @Test
    fun fourFavoriteSlotsKeepExistingPinOrder() {
        val installed = listOf("a", "b", "c", "d", "e", "f")
        val pinned = listOf("f", "e", "d", "c", "b", "a")
        assertEquals(listOf("f", "e", "d", "c"), dockApps(installed, pinned, listOf("a")))
    }

    @Test
    fun homeControlsStayOutOfSuggestionsButCanBePinned() {
        val settings = "com.android.tv.settings"
        val store = "com.android.vending"
        val installed = listOf("kodi", settings, store, "youtube")
        assertEquals(listOf("youtube", "kodi"), dockApps(installed, emptyList(), listOf(settings, "youtube")))
        assertEquals(listOf(store, "kodi", "youtube"), dockApps(installed, listOf(store), emptyList()))
    }

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
