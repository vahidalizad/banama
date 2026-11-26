package tv.scenichome.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeFocusTest {
    @Test
    fun waitsForCatalogsAndPrefersResumeThenSuggestions() {
        assertNull(initialHomeFocus(null, 4, waitingForResume = true, waitingForSuggestions = false))
        assertEquals("home/hero/9", initialHomeFocus(9, 4, waitingForResume = true, waitingForSuggestions = false))
        assertEquals("home/suggested/4", initialHomeFocus(null, 4, waitingForResume = false, waitingForSuggestions = false))
        assertEquals("home/search", initialHomeFocus(null, null, waitingForResume = false, waitingForSuggestions = false))
    }
}
