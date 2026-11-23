package tv.scenichome.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class LauncherPolicyTest {
    private var now = ZonedDateTime.parse("2026-11-23T19:59+01:00[Europe/Amsterdam]")
    private var calls = 0
    private val photos = mapOf("cached" to "Cached image", "next" to "Next image")
    private val rotation = PhotoRotation({ now }) { id ->
        calls++
        firstUsablePhoto(listOf("invalid", "cached", "next"), id, photos::get)
    }
    private val start = RotationState("fallback", now.withMinute(0).toEpochSecond(), paused = false)

    @Test
    fun rotatesAtAnHourBoundaryAndOnlyOnceInThatHour() {
        assertNull(rotation.advance(start))
        now = now.plusMinutes(1)
        val result = requireNotNull(rotation.advance(start))
        assertEquals("cached", result.state.photoId)
        assertEquals("Cached image", result.photo)
        assertNull(rotation.advance(result.state))
        assertEquals(1, calls)
    }

    @Test
    fun skipsInvalidImagesAndKeepsThePhotoIfNothingIsAvailable() {
        val offline = PhotoRotation({ now }) { id ->
            firstUsablePhoto(listOf("invalid", "cached"), id, photos::get)
        }
        val cached = requireNotNull(offline.advance(start, manual = true))
        assertEquals("cached", cached.state.photoId)
        val same = requireNotNull(offline.advance(cached.state, manual = true))
        assertNull(same.photo)
        assertEquals("cached", same.state.photoId)
    }

    @Test
    fun pauseSurvivesManualChangeAndResumeCatchesUpAfterSeveralHours() {
        now = now.plusHours(4)
        val paused = start.copy(paused = true)
        assertNull(rotation.advance(paused))
        val manual = requireNotNull(rotation.advance(paused, manual = true))
        assertTrue(manual.state.paused)
        assertNull(rotation.advance(manual.state.copy(paused = false)))
        val resumed = requireNotNull(rotation.advance(paused.copy(paused = false)))
        assertFalse(resumed.state.paused)
        assertEquals("cached", resumed.state.photoId)
        assertEquals(now.withMinute(0).toEpochSecond(), resumed.state.hour)
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
