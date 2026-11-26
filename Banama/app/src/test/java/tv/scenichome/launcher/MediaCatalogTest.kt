package tv.scenichome.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogTest {
    @Test
    fun profileUsesTheServerTierAndDaysAndHandlesMissingAccountDetails() {
        val account = JSONObject("""
            {"user_id":10,"user_name":"June Park","user_type":"golden",
            "user_expire":{"days":"12"},"user_allowed_to_stream":true}
        """).readAccount()
        assertEquals("June Park", account.username)
        assertEquals("JP", account.initials)
        assertEquals("Gold", account.tier)
        assertEquals(12, account.days)
        assertTrue(account.stream)
        assertEquals(0, JSONObject("""{"user_expire":{"days":-2}}""").readAccount().days)
        assertEquals(null, JSONObject().readAccount().days)
        assertEquals("", JSONObject().readAccount().initials)
    }

    @Test
    fun homeOnlyReadsWaitUntilHomeReturnsAndRetriesIgnoreDeadlines() {
        val deadlines = mutableMapOf("account" to 100L, "home" to 110L, "watch" to 120L)
        val home = RefreshSchedule(true, null, true, deadlines)
        assertEquals(listOf("notify", "liked"), home.due(90))
        val title = RefreshSchedule(false, null, true, deadlines)
        assertEquals(emptyList<String>(), title.due(90))
        assertEquals(10L, title.delayFrom(90))
        val watch = RefreshSchedule(false, MediaLibrary.WATCHLIST, true, deadlines)
        assertEquals(listOf("account", "watch"), watch.due(90, force = true))
        deadlines["notify"] = 125
        deadlines["liked"] = 125
        assertEquals(listOf("account", "home"), home.due(110))
        assertEquals(listOf("home"), RefreshSchedule(true, null, false, emptyMap()).due(90))
    }

    @Test
    fun onlyExplicitMovieTypesCanSkipSeriesDiscovery() {
        fun type(options: String) =
            JSONObject("""{"id":1,"title":{"english":"A"},"options":$options}""").mediaTitle()!!.type
        assertEquals(TitleType.UNKNOWN, type("{}"))
        assertEquals(TitleType.MOVIE, type("""{"is_series":false}"""))
        assertEquals(TitleType.MOVIE, type("""{"title_type":"movie"}"""))
        assertEquals(TitleType.SERIES, type("""{"is_series":false,"title_type":"series"}"""))
    }

    @Test
    fun emptyLibraryReplyDoesNotMaskAccountOrServerErrors() {
        val reply = JSONObject("""
            {"success":false,"error":true,"status":200,"result":{"page":1,"pages":1,"posts":[]}}
        """)
        assertTrue(reply.isEmptyLibraryResponse())
        assertTrue(reply.getJSONObject("result").titlePage().titles.isEmpty())
        assertFalse(reply.put("status", 401).isEmptyLibraryResponse())
        assertFalse(reply.put("status", 200).put("result", JSONObject()).isEmptyLibraryResponse())
    }

    @Test
    fun librarySearchMatchesEnglishAndPersianTitlesAndYear() {
        val title = JSONObject("""
            {"id":1,"title":{"english":"Keyvan","local":"کیوان"},"info":{"year":2024}}
        """).mediaTitle()!!
        assertTrue(title.matchesLibraryQuery("كِيوان"))
        assertTrue(title.matchesLibraryQuery("KEYVAN 2024"))
        assertFalse(title.matchesLibraryQuery("Keyvan 2023"))
    }

    @Test
    fun progressivePlaybackRequiresTheRequestedOffset() {
        assertTrue(isUhd(3840, 1600))
        assertFalse(isUhd(2560, 1440))
        assertEquals(12_000_000L, rangeSize(206, "bytes 1048576-1048576/12000000", 1048576))
        assertEquals(7_500_000_000L, rangeSize(206, "bytes 0-0/7500000000", 0))
        assertEquals(0L, rangeSize(206, "bytes 0-0/*", 0))
        assertEquals(null, rangeSize(200, "bytes 1048576-1048576/12000000", 1048576))
        assertEquals(null, rangeSize(206, "bytes 0-0/12000000", 1048576))
        assertEquals(null, rangeSize(206, "bytes 0-1/1", 0))
    }

    @Test
    fun apiModelsKeepEpisodeIdentityAndSkipNonUhdReleases() {
        val stream = JSONObject("""
            {"continue_watching":{"file_id":19,"time":43},"hls":[
                {"info":{"file_id":19,"season":2,"episode_number":3},"file":{
                    "m3u8":[{"default":"https://media.example/stream.m3u8"}],
                    "subtitle":{"fa":"https://media.example/episode.vtt"}},"options":{"token":"test-progress",
                        "intro_start":0,"intro_end":"00:01:03.500","end":"00:42:12"}}
            ]}
        """).playbackCatalog()
        assertEquals(19, stream.resumeFile)
        assertEquals(43000L, stream.resumeMs)
        assertEquals(2, stream.episodes.single().season)
        assertEquals(3, stream.episodes.single().number)
        assertEquals("fa", stream.episodes.single().captions.single().language)
        assertEquals("test-progress", stream.episodes.single().progressToken)
        assertEquals(WatchPosition(EpisodeNumber(2, 3), 43000L), stream.savedPosition)
        assertEquals(PlaybackMarkers(0L..<63_500L, 2_532_000L), stream.episodes.single().markers)

        val releases = JSONObject("""
            {"download":{"Season 2":[
                {"season_int":2,"resolution":"3840x1600","link":[{"episode":3,"dl":"https://media.example/uhd.mkv"}]},
                {"season_int":2,"quality":"1080p","encoder":"PSA","source":"WEB-DL","link":[
                    {"episode":3,"dl":"https://media.example/hd.mp4?token=test","english_subtitle":"https://media.example/en.srt"}]},
                {"season_int":2,"quality":"2160p","link":[{"episode":4,"dl":"http://media.example/insecure.mkv"}]}
            ]}}
        """).downloads()
        assertEquals(listOf(
            DownloadVideo(2, 3, "https://media.example/uhd.mkv", emptyList(), 2160, container = "mkv"),
            DownloadVideo(2, 3, "https://media.example/hd.mp4?token=test", listOf(Caption("en", "https://media.example/en.srt")),
                1080, "PSA", "mp4", "WEB-DL"),
        ), releases)
    }

    @Test
    fun retainsBothCroppedUhdEncodesAndTheirReleaseLabels() {
        val releases = JSONObject("""
            {"download":[
                {"quality":"x265 BluRay UHD 2160p","resolution":"3840x1608","encoder":"PaHe","size":"8.76 GB",
                    "dl":"https://media.example/pahe.mkv"},
                {"quality":"x265 BrRip 2160p","resolution":"3840x1608","encoder":"PSA","size":"4.68 GB",
                    "dl":"https://media.example/psa.mkv"}
            ]}
        """).downloads()
        assertEquals(listOf(2160, 2160), releases.map(DownloadVideo::resolution))
        assertEquals(listOf("PaHe", "PSA"), releases.map(DownloadVideo::encoder))
        assertEquals("8.76 GB", releases.first().size)
    }

    @Test
    fun includesDownloadOnlyEpisodesEvenWhenOtherEpisodesHaveHls() {
        val first = MediaEpisode(28, 2, 1, "", "https://media.example/stream.m3u8", emptyList())
        val catalog = PlaybackCatalog(listOf(first), 28, 1000L)
        val releases = JSONObject("""
            {"download":{"s2":[{"link":[
                {"episode":"Episode 1","quality":"2160p","dl":"https://media.example/first.mkv"},
                {"episode":"S02E02","tags":["4K"],"dl":"https://media.example/second.mkv"},
                {"episode":"02","resolution":"3840×1600","dl":"https://media.example/second-alt.mkv"},
                {"episode":"3","quality":"1080p","dl":"https://media.example/third.mkv"}
            ]}]}}
        """).downloads()
        assertEquals(listOf(1, 2, 2, 3), releases.map(DownloadVideo::episode))
        assertTrue(releases.all { it.season == 2 })

        val merged = catalog.includeDownloads(releases)
        assertEquals(first, merged.episodes.first())
        assertEquals(listOf(1, 2, 3), merged.episodes.map(MediaEpisode::number))
        assertEquals(null, merged.episodes.last().hls)
        assertEquals(28, merged.resumeFile)
    }

    @Test
    fun advancedSearchSendsMultipleGenresAndBoundsTheSelectedYears() {
        val filters = JSONObject(browseParameters("series", setOf("drama", "comedy"), setOf(1998, 2024), null, SearchOrder.DEFAULT))
        assertEquals("""["drama","comedy"]""", filters.getJSONArray("genre").toString())
        assertEquals("series", filters.getString("type"))
        assertEquals("1998", filters.getString("min_year"))
        assertEquals("2024", filters.getString("max_year"))
        assertEquals("imdb-rate", SearchOrder.RANK.parameter)
        val newest = JSONObject(browseParameters(null, emptySet(), emptySet(), null, SearchOrder.RELEASE))
        assertEquals(FIRST_RELEASE_YEAR.toString(), newest.getString("min_year"))
        assertTrue(newest.getString("max_year").toInt() >= 2024)
    }

    @Test
    fun exactYearsAndAllSelectedGenresWorkWithLocalSearchAndReleaseSorting() {
        val titles = JSONObject("""
            {"posts":[
                {"id":1,"title":{"english":"A"},"info":{"year":1998},
                    "genre":[{"name":"Drama","slug":"drama"},{"name":"Comedy","slug":"comedy"}],
                    "score":{"imdb":{"score":"8.1"}}},
                {"id":2,"title":{"english":"B"},"info":{"year":2023},
                    "genre":[{"name":"Drama","slug":"drama"},{"name":"Comedy","slug":"comedy"}]},
                {"id":3,"title":{"english":"C"},"info":{"year":2024},
                    "genre":[{"name":"Comedy","slug":"comedy"},{"name":"Drama","slug":"drama"}],
                    "score":{"imdb":{"score":"7.2"}}},
                {"id":4,"title":{"english":"D"},"info":{"year":2024},"genre":[{"name":"Drama","slug":"drama"}]}
            ]}
        """).titlePage().titles
        val selected = setOf(BrowseOption("Comedy", "comedy"), BrowseOption("Drama", "drama"))
        val filtered = titles.filter { it.matchesRelease(setOf(1998, 2024)) && it.matchesGenre(selected) }
        assertEquals(listOf(3, 1), SearchOrder.RELEASE.sort(filtered).map(MediaTitle::id))
        assertEquals(listOf(1, 3), SearchOrder.RANK.sort(filtered).map(MediaTitle::id))
    }

    @Test
    fun watchHistorySupportsZeroTimeAndFiltersOutAlreadyPlayedEpisodes() {
        val history = JSONObject("""
            {"posts":[{"id":22,"title":{"english":"Series"},"options":{"is_series":true},
                "data":{"season":1,"episode":"10","time":0}},
                {"id":23,"title":{"english":"Movie"},"data":{"time":3720}}]}
        """).watchHistory()
        val watched = history.getValue(22)
        assertEquals(WatchPosition(EpisodeNumber(1, 10), 0L), watched.position)
        assertEquals(WatchPosition(EpisodeNumber(0, 0), 3_720_000L), history.getValue(23).position)

        val catalog = JSONObject("""
            {"continue_watching":{"file_id":17,"time":0},"hls":[
                {"info":{"file_id":30,"season":2,"episode_number":1},"file":{"m3u8":[{"default":"https://media.example/s2.m3u8"}]}},
                {"info":{"file_id":17,"season":1,"episode_number":10},"file":{"m3u8":[{"default":"https://media.example/s1.m3u8"}]}},
                {"info":{"file_id":31,"season":2,"episode_number":1},"file":{"m3u8":[{"default":"https://media.example/alt.m3u8"}]}}
            ]}
        """).playbackCatalog().includeDownloads(listOf(DownloadVideo(1, 11, "https://media.example/uhd.mkv", emptyList())))
        assertEquals(watched.position, catalog.savedPosition)
        val episodes = catalog.seriesEpisodes(watched.title)
        assertEquals(null, episodes.nextAfter(null))
        assertEquals(EpisodeNumber(1, 11), episodes.nextAfter(watched.position.episode)?.episode)
        assertEquals(EpisodeNumber(2, 1), episodes.nextAfter(EpisodeNumber(1, 11))?.episode)
        assertEquals(null, episodes.nextAfter(EpisodeNumber(2, 1)))
        val lastInSeason = catalog.episodes.first { it.fileId == 17 }
        val download = catalog.nextEpisode(lastInSeason)!!
        assertEquals(EpisodeNumber(1, 11), download.key)
        val nextSeason = catalog.nextEpisode(download)!!
        assertEquals(EpisodeNumber(2, 1), nextSeason.key)
        assertEquals(null, catalog.nextEpisode(nextSeason))
    }

    @Test
    fun heroOrdersNextEpisodesAndResumesByWatchActivityAndHidesWholeTitles() {
        fun card(id: Int, number: Int): UpcomingEpisode {
            val title = JSONObject("""{"id":$id,"title":{"english":"Series $id"}}""").mediaTitle()!!
            return UpcomingEpisode(title, EpisodeNumber(1, number))
        }
        val history = listOf(card(2, 1), card(1, 3), card(3, 5))
        val upcoming = listOf(card(5, 2), card(1, 4), card(2, 2), card(4, 3))
        val playedOnTv = mapOf(4 to 200L, 3 to 100L, 1 to 0L)

        assertEquals(
            listOf(card(4, 3), card(3, 5), card(2, 2), card(1, 4), card(5, 2)),
            orderedHeroEpisodes(history, upcoming, playedOnTv, emptySet()),
        )
        assertEquals(
            listOf(card(4, 3), card(3, 5), card(2, 2)),
            orderedHeroEpisodes(history, upcoming, playedOnTv, setOf(1, 5)),
        )
    }

    @Test
    fun seriesDetailsKeepCertificationCountriesAndCastRoles() {
        val title = JSONObject("""
            {"id":22,"title":{"english":"Series"},"info":{"age":"TV-MA","year":2025,"time":{"default":50}},
                "country":[{"name":"United Kingdom"},{"name":"United States"}],
                "score":{"imdb":{"score":8.3,"votes":114200},"30nama":{"score":8.54},
                    "rottentomatoes":{"score":"71%"},"metacritic":{"score":0}},
                "cast":[{"name":"Tom Hardy","as":"Harry Da Souza","image":{"poster":{"webp":{"medium":"https://media.example/actor.webp"}}}}]}
        """).mediaTitle()!!
        assertEquals("TV-MA", title.ageRating)
        assertEquals(listOf("United Kingdom", "United States"), title.countries)
        assertEquals(listOf(
            MediaRating(RatingSource.IMDB, "8.3", 114200),
            MediaRating(RatingSource.NAMA, "8.54", 0),
            MediaRating(RatingSource.ROTTEN_TOMATOES, "71%", 0),
        ), title.ratings)
        assertEquals(CastMember("Tom Hardy", "Harry Da Souza", "https://media.example/actor.webp"), title.cast.single())
    }
}
