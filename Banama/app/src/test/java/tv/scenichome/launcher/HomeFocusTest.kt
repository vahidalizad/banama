package tv.scenichome.launcher

import org.json.JSONObject
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

    @Test
    fun backdropFollowsTheSelectedHistoryOrSuggestionEvenWithoutArtwork() {
        val titles = (1..6).map { id ->
            JSONObject("""{"id":$id,"title":{"english":"Title $id"},"options":{"title_type":"movie"}}""").mediaTitle()!!
        }
        val movie = UpcomingEpisode(titles.last(), EpisodeNumber(0, 0))
        assertEquals(movie.title, homeBackdropTitle(movie, titles, "home/suggested/2"))
        assertEquals(titles[4], homeBackdropTitle(null, titles, "home/suggested/5"))
        assertEquals(titles.first(), homeBackdropTitle(null, titles, "home/suggested/99"))
        assertNull(homeBackdropTitle(null, emptyList(), ""))
    }
}
