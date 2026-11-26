package tv.scenichome.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRedirectServiceTest {
    @Test
    fun redirectOnlyTheGoogleTvHomeActivity() {
        val stock = "com.google.android.apps.tv.launcherx"
        val home = "$stock.home.HomeActivity"
        assertTrue(isGoogleTvHome(stock, StringBuilder(home)))
        assertFalse(isGoogleTvHome(stock, "$stock.settings.SettingsActivity"))
        assertFalse(isGoogleTvHome("com.android.tv.settings", home))
        assertFalse(isGoogleTvHome(stock, null))
        assertFalse(isGoogleTvHome(null, home))
    }
}
