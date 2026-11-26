package tv.scenichome.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRedirectServiceTest {
    @Test
    fun redirectsOnlyDeclaredHomeWindowsAndIgnoresDuplicateEvents() {
        val watch = HomeRedirectWatch("tv.stock", setOf("tv.stock.HomeAlias", "tv.stock.Home"))

        assertFalse(watch.shouldRedirect("tv.other", "tv.stock.Home", 0))
        assertFalse(watch.shouldRedirect("tv.stock", "tv.stock.Settings", 0))
        assertFalse(watch.shouldRedirect("tv.stock", null, 0))
        assertTrue(watch.shouldRedirect("tv.stock", StringBuilder("tv.stock.Home"), 0))
        assertFalse(watch.shouldRedirect("tv.stock", "tv.stock.HomeAlias", 599))
        assertTrue(watch.shouldRedirect("tv.stock", "tv.stock.HomeAlias", 600))
    }
}
