package tv.scenichome.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSubtitleTest {
    @Test
    fun omitsPlaceholderCaptionsWhileKeepingNamedAndLanguageTaggedTracks() {
        assertNull(subtitleDisplayName(null, null))
        assertNull(subtitleDisplayName("und", "Subtitle 1"))
        assertNull(subtitleDisplayName("1", null))
        assertEquals("Persian CC", subtitleDisplayName("und", "Persian CC"))
        assertNotNull(subtitleDisplayName("fa", "Subtitle 1"))
    }

    @Test
    fun languagePreferenceMatchesBothEnglishAndPersianAcrossSources() {
        assertEquals("en", subtitlePreferenceKey("eng", "Track 1"))
        assertEquals("en", subtitlePreferenceKey("en-US", "English CC"))
        assertEquals("en", subtitlePreferenceKey("und", "English (SDH)"))
        assertEquals("fa", subtitlePreferenceKey("per", "Track 2"))
        assertEquals("fa", subtitlePreferenceKey("fas", "Persian"))
        assertEquals("fa", subtitlePreferenceKey("fa_IR", "Farsi"))
        assertEquals("fa", subtitlePreferenceKey(null, "فارسی"))
    }

    @Test
    fun positionedCuesGetASmallInsetAndDefaultCuesStayNearTheBottom() {
        val (inset, extra) = subtitleBottomInsets(1080, 1080, 0, 40)
        assertEquals(43, inset)
        assertEquals(129.6f, inset + (1080 - inset) * extra, .1f)
    }

    @Test
    fun controlsOnlyReserveTheSpaceTheyCoverAboveLetterboxing() {
        assertEquals(220 to 0f, subtitleBottomInsets(1080, 800, 320, 40))
        assertEquals(440 to 0f, subtitleBottomInsets(1080, 800, 700, 40))
    }
}
