package tv.scenichome.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogTest {
    @Test
    fun titleSearchAcceptsPersianKeyboardVariantsAndTitleYear() {
        assertTrue(matchesTitle("کِیوان", "Keyvan 2024", "كيوان"))
        assertTrue(matchesTitle("KEYVAN 2024", "Keyvan 2024", "کیوان"))
        assertFalse(matchesTitle("Keyvan 2023", "Keyvan 2024", "کیوان"))
    }

    @Test
    fun progressivePlaybackRequiresTheRequestedOffset() {
        assertTrue(isUhd(3840, 1600))
        assertFalse(isUhd(2560, 1440))
        assertTrue(rangeAccepted(206, "bytes 1048576-1048576/12000000", 1048576))
        assertFalse(rangeAccepted(200, "bytes 1048576-1048576/12000000", 1048576))
        assertFalse(rangeAccepted(206, "bytes 0-0/12000000", 1048576))
        assertFalse(rangeAccepted(206, "bytes 0-1/1", 0))
    }

    @Test
    fun apiModelsKeepEpisodeIdentityAndSkipNonUhdReleases() {
        val stream = JSONObject("""
            {"continue_watching":{"file_id":19,"time":43},"hls":[
                {"info":{"file_id":19,"season":2,"episode_number":3},"file":{
                    "m3u8":[{"default":"https://media.example/stream.m3u8"}],
                    "subtitle":{"fa":"https://media.example/episode.vtt"}}}
            ]}
        """).playbackCatalog()
        assertEquals(19, stream.resumeFile)
        assertEquals(43000L, stream.resumeMs)
        assertEquals(2, stream.episodes.single().season)
        assertEquals(3, stream.episodes.single().number)
        assertEquals("fa", stream.episodes.single().captions.single().language)

        val releases = JSONObject("""
            {"download":{"Season 2":[
                {"season_int":2,"resolution":"3840x1600","link":[{"episode":3,"dl":"https://media.example/uhd.mkv"}]},
                {"season_int":2,"quality":"1080p","link":[{"episode":3,"dl":"https://media.example/hd.mkv"}]},
                {"season_int":2,"quality":"2160p","link":[{"episode":4,"dl":"http://media.example/insecure.mkv"}]}
            ]}}
        """).downloads()
        assertEquals(listOf(DownloadVideo(2, 3, "https://media.example/uhd.mkv", emptyList())), releases)
    }

    @Test
    fun advancedSearchSendsGenreAsAnArray() {
        val filters = JSONObject(browseParameters(mapOf("genre" to "drama", "type" to "series")))
        assertEquals("drama", filters.getJSONArray("genre").getString(0))
        assertEquals("series", filters.getString("type"))
    }
}
