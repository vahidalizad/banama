package tv.scenichome.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal enum class TitleType { MOVIE, SERIES, UNKNOWN }

internal data class MediaTitle(
    val id: Int,
    val name: String,
    val localized: String,
    val year: Int,
    val type: TitleType,
    val poster: String?,
    val cover: String?,
    val description: String,
    val runtime: Int,
    val seasons: Int,
    val genres: List<String>,
    val genreKeys: Set<String>,
    val ratings: List<MediaRating>,
    val nextEpisode: String,
    val freeStream: Boolean,
    val freeDownload: Boolean,
    val ageRating: String,
    val countries: List<String>,
    val cast: List<CastMember>,
) {
    val series get() = type == TitleType.SERIES
}

internal data class CastMember(val name: String, val role: String, val photo: String?)
internal enum class RatingSource(val key: String, val label: String) {
    IMDB("imdb", "IMDb"),
    NAMA("30nama", "30nama"),
    ROTTEN_TOMATOES("rottentomatoes", "Rotten Tomatoes"),
    METACRITIC("metacritic", "Metacritic"),
    MY_DRAMA_LIST("mydramalist", "MyDramaList"),
    MY_ANIME_LIST("myanimelist", "MyAnimeList"),
}

internal data class MediaRating(val source: RatingSource, val score: String, val votes: Int)

internal data class TitlePage(val titles: List<MediaTitle>, val pages: Int)
internal data class BrowseOption(val label: String, val value: String)
internal const val FIRST_RELEASE_YEAR = 1888
internal fun currentReleaseYear() = GregorianCalendar().get(Calendar.YEAR)

internal enum class SearchOrder(val label: String, val parameter: String) {
    DEFAULT("Default order", "update"),
    RELEASE("Newest releases", "year"),
    RANK("IMDb rank", "imdb-rate");

    fun sort(titles: List<MediaTitle>) = when (this) {
        DEFAULT -> titles
        RELEASE -> {
            val range = FIRST_RELEASE_YEAR..currentReleaseYear()
            titles.sortedByDescending { it.year.takeIf(range::contains) ?: 0 }
        }
        RANK -> titles.sortedByDescending {
            it.ratings.firstOrNull { rating -> rating.source == RatingSource.IMDB }?.score?.toDoubleOrNull() ?: 0.0
        }
    }
}
internal data class MediaAccount(
    val days: Int?,
    val stream: Boolean,
    val download: Boolean,
    val userId: Int,
    val username: String,
    val type: String,
) {
    val tier: String
        get() = when (type) {
            "golden" -> "Gold"
            "blue" -> "Blue"
            "red" -> "Red"
            "free" -> "Free"
            "staff" -> "Staff"
            "admin" -> "Admin"
            "robot" -> "Robot"
            else -> "30nama membership"
        }

    val initials: String
        get() = username.split(' ', '.', '_', '-').asSequence()
            .mapNotNull { part -> part.firstOrNull(Char::isLetterOrDigit) }
            .take(2).joinToString("") { it.uppercaseChar().toString() }
}

internal fun JSONObject.readAccount() = MediaAccount(
    days = optJSONObject("user_expire")?.text("days")?.toIntOrNull()?.coerceAtLeast(0),
    stream = optBoolean("user_allowed_to_stream"),
    download = optBoolean("user_allowed_to_download"),
    userId = optInt("user_id"),
    username = text("user_name").trim(),
    type = text("user_type").trim().lowercase(Locale.ROOT),
)
internal data class Caption(val language: String, val url: String)
internal data class EpisodeNumber(val season: Int, val episode: Int) : Comparable<EpisodeNumber> {
    override fun compareTo(other: EpisodeNumber) = compareValuesBy(this, other, EpisodeNumber::season, EpisodeNumber::episode)
}

internal data class WatchPosition(val episode: EpisodeNumber, val timeMs: Long)
internal data class WatchedTitle(val title: MediaTitle, val position: WatchPosition)
internal data class UpcomingEpisode(val title: MediaTitle, val episode: EpisodeNumber)

internal data class MediaEpisode(
    val fileId: Int,
    val season: Int,
    val number: Int,
    val name: String,
    val hls: String?,
    val captions: List<Caption>,
    val progressToken: String = "",
    val markers: PlaybackMarkers = PlaybackMarkers(null, null),
) {
    val key get() = EpisodeNumber(season, number)

    fun observerForm(titleId: Int, userId: Int, timeMs: Long, durationMs: Long): Map<String, String>? {
        if (userId <= 0 || fileId <= 0 || progressToken.isBlank()) return null
        // 30nama ignores positions between its 30-second checkpoints.
        val checkpointSeconds = (timeMs / 30_000) * 30
        if (checkpointSeconds == 0L) return null
        return mapOf(
            "post_id" to titleId.toString(), "file_id" to fileId.toString(),
            "user_id" to userId.toString(), "time" to checkpointSeconds.toString(),
            "duration" to (durationMs / 1000).toString(), "token" to progressToken,
        )
    }
}

internal data class PlaybackMarkers(val intro: LongRange?, val creditsMs: Long?)

internal data class DownloadVideo(
    val season: Int,
    val episode: Int,
    val url: String,
    val captions: List<Caption>,
    val resolution: Int? = null,
    val encoder: String = "",
    val container: String = "",
    val edition: String = "",
    val id: Int = 0,
    val size: String = "",
)
internal data class PlaybackCatalog(
    val episodes: List<MediaEpisode>,
    val resumeFile: Int,
    val resumeMs: Long,
) {
    val savedPosition: WatchPosition?
        get() = episodes.firstOrNull { resumeFile > 0 && it.fileId == resumeFile }
            ?.let { WatchPosition(it.key, resumeMs) }

    fun nextEpisode(after: MediaEpisode): MediaEpisode? =
        episodes.filter { it.key > after.key }.minByOrNull(MediaEpisode::key)
}

internal fun PlaybackCatalog.seriesEpisodes(title: MediaTitle): List<UpcomingEpisode> =
    episodes.filter { it.number > 0 }
        .distinctBy(MediaEpisode::key).sortedBy(MediaEpisode::key)
        .map { UpcomingEpisode(title, it.key) }

internal fun List<UpcomingEpisode>.nextAfter(watched: EpisodeNumber?): UpcomingEpisode? =
    watched?.let { previous -> firstOrNull { it.episode > previous } }

internal fun orderedHeroEpisodes(
    history: List<UpcomingEpisode>,
    upcoming: List<UpcomingEpisode>,
    watchedAt: Map<Int, Long>,
    hidden: Set<Int>,
): List<UpcomingEpisode> {
    // A next episode replaces the resume card without moving it out of watch-history order.
    return (history + upcoming).associateBy { it.title.id }.values
        .filterNot { it.title.id in hidden }
        .sortedByDescending { watchedAt[it.title.id] ?: 0L }
}

internal fun isUhd(width: Int, height: Int) = width >= 3840 || height >= 2160

internal fun browseParameters(
    type: String?,
    genres: Set<String>,
    years: Set<Int>,
    rating: String?,
    order: SearchOrder,
) = JSONObject().apply {
    type?.let { put("type", it) }
    rating?.let { put("imdb_rate", it) }
    if (genres.isNotEmpty()) put("genre", JSONArray(genres))
    if (years.isNotEmpty() || order == SearchOrder.RELEASE) {
        // 30nama requires both bounds and otherwise puts invalid years first.
        put("min_year", (years.minOrNull() ?: FIRST_RELEASE_YEAR).toString())
        put("max_year", (years.maxOrNull() ?: currentReleaseYear()).toString())
    }
}.toString()

internal fun MediaTitle.matchesRelease(years: Set<Int>) = years.isEmpty() || year in years

internal fun MediaTitle.matchesGenre(choices: Set<BrowseOption>) = choices.all {
    it.value.lowercase(Locale.ROOT) in genreKeys || it.label.lowercase(Locale.ROOT) in genreKeys
}

internal fun MediaTitle.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\p{M}\\u0640\\u200c]"), "")
    val text = normalize("$name $localized $year")
    return normalize(query).split(Regex("\\s+")).all { it in text }
}

internal fun rangeSize(status: Int, range: String?, offset: Long): Long? {
    if (status != 206) return null
    val fields = range?.let { Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(it) } ?: return null
    val first = fields.groupValues[1].toLongOrNull() ?: return null
    val last = fields.groupValues[2].toLongOrNull() ?: return null
    if (first != offset || last != offset) return null
    val total = fields.groupValues[3]
    return if (total == "*") 0L else total.toLongOrNull()?.takeIf { it > last }
}

internal fun httpsUri(value: String?): URI? = value?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1, 443)
    }
}

internal class NamaFailure(
    val rejectedSession: Boolean = false,
    val browserChallenge: Boolean = false,
) : IOException()

internal class NamaApi(
    address: String = BuildConfig.THIRTY_NAMA_API,
    private val key: String = BuildConfig.THIRTY_NAMA_CLIENT,
) {
    private val base = httpsUri(address.trimEnd('/'))?.takeIf { it.query == null && it.fragment == null }
    val configured = base != null && key.isNotBlank()

    suspend fun call(action: String, session: String? = null, form: Map<String, String> = emptyMap()) =
        read(action, session, form) { it }

    suspend fun <T> read(
        action: String,
        session: String? = null,
        form: Map<String, String> = emptyMap(),
        decode: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (!configured) throw NamaFailure()
        val connection = httpsUri("$base/action/$action")!!.toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = if (action == "advanced_search" || action == "user_list") 25_000 else 9_000
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("c-api-key", key)
        connection.setRequestProperty("c-version-number", BuildConfig.VERSION_NAME)
        connection.setRequestProperty("c-language", if (Locale.getDefault().language == "fa") "fa" else "en")
        session?.let { connection.setRequestProperty("c-token", it) }
        try {
            val encoded = form.entries.joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401) throw NamaFailure(rejectedSession = true)
            if (status !in 200..299) {
                throw NamaFailure(browserChallenge = connection.getHeaderField("cf-mitigated") == "challenge")
            }
            if (action == "observer") return@withContext decode(JSONObject())
            val result = connection.inputStream.use { JSONObject(it.readBounded(4 * 1024 * 1024).decodeToString()) }
            val emptyList = action == "user_list" && form["action"] == "list" && result.isEmptyLibraryResponse()
            if (action != "qrlogin_login" && !emptyList && (!result.optBoolean("success") || result.optBoolean("error"))) {
                throw NamaFailure(rejectedSession = result.optInt("status") == 401)
            }
            decode(result.optJSONObject("result") ?: JSONObject())
        } finally {
            connection.disconnect()
        }
    }
}

internal fun JSONObject.isEmptyLibraryResponse(): Boolean {
    if (optInt("status") != 200) return false
    val page = optJSONObject("result") ?: return false
    return page.has("page") && page.optInt("pages", -1) in 0..1 && page.has("posts") &&
        (page.isNull("posts") || page.optJSONArray("posts")?.length() == 0)
}

internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONObject.art(key: String): String? =
    sequenceOf(text(key), optJSONObject(key)?.text("webp"), optJSONObject(key)?.text("jpg"))
        .firstOrNull { httpsUri(it) != null }

private fun JSONObject.poster(size: String): String? {
    val poster = optJSONObject("image")?.optJSONObject("poster") ?: return null
    return sequenceOf(poster.optJSONObject("webp")?.text(size), poster.optJSONObject("jpg")?.text(size), poster.text("preview"))
        .firstOrNull { httpsUri(it) != null }
}

internal fun JSONObject.mediaTitle(): MediaTitle? {
    val id = optInt("id")
    val title = optJSONObject("title") ?: return null
    val name = title.text("english").ifBlank { title.text("local") }
    if (id <= 0 || name.isBlank()) return null
    val info = optJSONObject("info")
    val options = optJSONObject("options")
    val plot = optJSONObject("plot")
    val scores = optJSONObject("score")
    val genres = optJSONArray("genre").objects()
    val ratings = RatingSource.entries.mapNotNull { source ->
        val entry = scores?.optJSONObject(source.key) ?: return@mapNotNull null
        val score = entry.text("score")
        if ((score.removeSuffix("%").toDoubleOrNull() ?: 0.0) > 0) {
            MediaRating(source, score, entry.optInt("votes").coerceAtLeast(0))
        } else null
    }
    return MediaTitle(
        id, name, title.text("local").takeUnless { it == name }.orEmpty(), info?.optInt("year") ?: 0,
        when {
            options?.optBoolean("is_series") == true || options?.text("title_type") == "series" -> TitleType.SERIES
            options?.text("is_series").equals("false", ignoreCase = true) || options?.text("title_type") == "movie" -> TitleType.MOVIE
            else -> TitleType.UNKNOWN
        },
        poster("medium"), optJSONObject("image")?.art("cover"), plot?.text("english")?.ifBlank { plot.text("local") }.orEmpty(),
        info?.optJSONObject("time")?.optInt("default") ?: 0, info?.optInt("seasons") ?: 0,
        genres.map { it.text("name") }.filter(String::isNotBlank),
        genres.flatMap { genre -> listOf("name", "slug", "value", "id").map(genre::text) }
            .filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) }.toSet(),
        ratings,
        optJSONObject("air")?.text("next_episode_caption").orEmpty(),
        options?.optBoolean("free_stream") == true, options?.optBoolean("free_download") == true,
        info?.text("age").orEmpty(),
        optJSONArray("country").objects().map { it.text("name") }.filter(String::isNotBlank),
        optJSONArray("cast").objects().mapNotNull { person ->
            person.text("name").takeIf(String::isNotBlank)?.let { CastMember(it, person.text("as"), person.poster("medium")) }
        }.take(3),
    )
}

internal fun JSONObject.titlePage() =
    TitlePage(optJSONArray("posts").objects().mapNotNull { it.mediaTitle() }.distinctBy(MediaTitle::id), optInt("pages", 1))

internal fun JSONObject.watchHistory(): Map<Int, WatchedTitle> =
    optJSONArray("posts").objects().mapNotNull { post ->
        val title = post.mediaTitle() ?: return@mapNotNull null
        val data = post.optJSONObject("data") ?: return@mapNotNull null
        if (data.isNull("time")) return@mapNotNull null
        val episode = if (title.series) {
            val season = data.text("season").seasonIndex() ?: return@mapNotNull null
            val number = data.episodeIndex() ?: return@mapNotNull null
            EpisodeNumber(season, number)
        } else EpisodeNumber(0, 0)
        title.id to WatchedTitle(title, WatchPosition(episode, data.watchTime()))
    }.toMap()

private fun JSONObject.watchTime() = optLong("time").coerceIn(0, Long.MAX_VALUE / 1000) * 1000L

private fun JSONObject.marker(key: String): Long? {
    val parts = text(key).trim().split(':')
    val tail = parts.last().toDoubleOrNull() ?: return null
    val seconds = when (parts.size) {
        1 -> tail
        2, 3 -> {
            if (tail !in 0.0..<60.0) return null
            val minutes = parts[parts.lastIndex - 1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            val hours = if (parts.size == 2) 0 else parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            hours * 3600.0 + minutes * 60 + tail
        }
        else -> return null
    }
    return seconds.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }
        ?.let { (it * 1000).toLong() }
}

internal fun JSONObject.playbackCatalog(): PlaybackCatalog {
    val recent = optJSONObject("continue_watching")
    val videos = optJSONArray("hls").objects().mapNotNull { entry ->
        val info = entry.optJSONObject("info") ?: return@mapNotNull null
        val file = entry.optJSONObject("file") ?: return@mapNotNull null
        val hls = file.optJSONArray("m3u8").objects().firstNotNullOfOrNull { variant ->
            sequenceOf("default", "2ch", "6ch").map(variant::text).firstOrNull { httpsUri(it) != null }
        } ?: return@mapNotNull null
        val subs = file.optJSONObject("subtitle")
        val captions = subs?.keys()?.asSequence()?.mapNotNull { lang ->
            subs.text(lang).takeIf { httpsUri(it) != null }?.let { Caption(lang, it) }
        }?.toList().orEmpty()
        val options = entry.optJSONObject("options")
        val introStart = options?.marker("intro_start")
        val introEnd = options?.marker("intro_end")
        val intro = if (introStart != null && introEnd != null && introStart < introEnd) introStart..<introEnd else null
        MediaEpisode(
            info.optInt("file_id"), info.text("season").seasonIndex() ?: 0, info.episodeIndex() ?: 0,
            info.text("episode_name"), hls, captions, options?.text("token").orEmpty(),
            PlaybackMarkers(intro, options?.marker("end")?.takeIf { it > 0 }),
        )
    }
    return PlaybackCatalog(videos, recent?.optInt("file_id") ?: 0, recent?.watchTime() ?: 0)
}

private val seasonPattern = Regex("""(?i)(?:s(?:eason)?\s*)?(\d+)""")
private val episodePattern = Regex("""(?i)(?:^|\W|\d)(?:episode\s*|e)(\d+)\b""")

private fun String.seasonIndex(): Int? = seasonPattern.matchEntire(trim())?.groupValues?.get(1)?.toIntOrNull()

private fun JSONObject.episodeIndex(): Int? = sequenceOf(text("episode_number"), text("episode"))
    .mapNotNull { it.trim().toIntOrNull() ?: episodePattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    .firstOrNull()

private val dimensionsPattern = Regex("""(?i)\b(\d{3,4})\s*[x×]\s*(\d{3,4})\b""")
private val progressivePattern = Regex("""(?i)(?<!\d)(\d{3,4})p\b""")
private val uhdPattern = Regex("""(?i)\b(?:4\s*k|uhd)\b""")
private val videoContainers = setOf("mkv", "mp4", "m4v", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "flv", "3gp", "m3u8")

private fun String.releaseResolution(): Int? {
    if (uhdPattern.containsMatchIn(this)) return 2160
    progressivePattern.find(this)?.let { return it.groupValues[1].toInt() }
    dimensionsPattern.find(this)?.let { return videoResolution(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    return trim().toIntOrNull()?.takeIf { it in 144..4320 }
}

internal fun JSONObject.downloads(): List<DownloadVideo> {
    val bySeason = optJSONObject("download")
    val rows = optJSONArray("download").objects().map { null to it } + bySeason?.keys()?.asSequence()
        ?.flatMap { key -> bySeason.optJSONArray(key).objects().map { key.seasonIndex() to it } }
        ?.toList().orEmpty()
    return rows.flatMap { (groupSeason, row) ->
        val season = row.text("season_int").seasonIndex() ?: row.text("season").seasonIndex() ?: groupSeason ?: 0
        val links = row.optJSONArray("link").objects().ifEmpty { listOf(row) }
        links.mapNotNull { link ->
            val url = link.text("dl")
            val uri = httpsUri(url) ?: return@mapNotNull null
            fun field(key: String) = link.text(key).trim().ifBlank { row.text(key).trim() }
            val captions = listOf("fa" to "persian_subtitle", "en" to "english_subtitle").mapNotNull { (lang, key) ->
                field(key).takeIf { httpsUri(it) != null }?.let { Caption(lang, it) }
            }
            val resolution = sequenceOf(link, row).flatMap { release ->
                sequenceOf("resolution", "quality", "tags").map(release::text)
            }.plus(uri.path.orEmpty()).firstNotNullOfOrNull(String::releaseResolution)
            val container = field("container").ifBlank { field("format") }.lowercase(Locale.ROOT)
                .ifBlank { uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in videoContainers }.orEmpty() }
            val quality = field("quality").takeUnless {
                progressivePattern.matches(it) || dimensionsPattern.matches(it) || uhdPattern.matches(it) || it.toIntOrNull() != null
            }.orEmpty()
            val source = field("source").takeUnless {
                "://" in it || it.length > 40 || it.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoContainers
            }.orEmpty()
            val edition = listOf(quality, source, field("version"))
                .filter(String::isNotBlank).distinct().joinToString(" · ")
            DownloadVideo(season, link.episodeIndex() ?: row.episodeIndex() ?: 0, url, captions,
                resolution, field("encoder"), container, edition, link.optInt("id"), field("size"))
        }
    }.distinctBy { it.url }
}

internal fun PlaybackCatalog.includeDownloads(downloads: List<DownloadVideo>): PlaybackCatalog {
    val existing = episodes.mapTo(mutableSetOf()) { it.season to it.number }
    return copy(episodes = episodes + downloads.mapNotNull { download ->
        if (existing.add(download.season to download.episode)) {
            MediaEpisode(0, download.season, download.episode, "", null, download.captions)
        } else null
    })
}

internal fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val block = ByteArray(8192)
    while (true) {
        val count = read(block)
        if (count < 0) return buffer.toByteArray()
        if (buffer.size() + count > limit) throw IOException()
        buffer.write(block, 0, count)
    }
}

private fun <T> readMedia(address: URI, range: Long? = null, read: (HttpsURLConnection) -> T): T? {
    var url = address
    repeat(5) {
        val connection = url.toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        if (range != null) {
            connection.setRequestProperty("Range", "bytes=$range-$range")
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode !in 300..399) return read(connection)
            val location = connection.getHeaderField("Location") ?: return null
            url = httpsUri(url.resolve(location).toString()) ?: return null
        } finally {
            connection.disconnect()
        }
    }
    return null
}

internal class ExpiredMediaLink : IOException()
internal fun isExpiredMediaStatus(status: Int) = status == 401 || status == 403 || status == 410

internal enum class DownloadRange { SEEKABLE, INVALID, UNREACHABLE }

internal suspend fun downloadRange(address: String): DownloadRange = withContext(Dispatchers.IO) {
    val uri = httpsUri(address) ?: return@withContext DownloadRange.UNREACHABLE
    try {
        fun sizeAt(offset: Long): Long? =
            readMedia(uri, offset) { connection ->
                val status = connection.responseCode
                if (isExpiredMediaStatus(status)) throw ExpiredMediaLink()
                if (status !in 200..299 && status != 416) throw IOException()
                val total = rangeSize(status, connection.getHeaderField("Content-Range"), offset)
                total?.takeIf { connection.inputStream.use { it.read() >= 0 && it.read() == -1 } }
            }
        val first = sizeAt(0) ?: return@withContext DownloadRange.INVALID
        if (first == 1L) return@withContext DownloadRange.INVALID
        val offset = if (first == 0L) 1_048_576L else minOf(1_048_576L, first - 1)
        val seek = sizeAt(offset) ?: return@withContext DownloadRange.INVALID
        if (first == 0L || seek == 0L || first == seek) DownloadRange.SEEKABLE else DownloadRange.INVALID
    } catch (expired: ExpiredMediaLink) {
        throw expired
    } catch (_: IOException) {
        DownloadRange.UNREACHABLE
    }
}

internal class MediaArtwork {
    private val cache = object : LruCache<Pair<String, Int>, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, Int>, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(url: String, size: Int): Bitmap? = cache.get(url to size)

    suspend fun image(url: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val uri = httpsUri(url) ?: return@withContext null
        val key = uri.toString() to size
        cache.get(key) ?: runCatching {
            readMedia(uri) { connection ->
                if (connection.responseCode != 200 || connection.contentLengthLong > 6 * 1024 * 1024) return@readMedia null
                val bytes = connection.inputStream.use { it.readBounded(6 * 1024 * 1024) }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@readMedia null
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > size) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }?.also {
                ensureActive()
                cache.put(key, it)
            }
        }.getOrNull()
    }

    fun clear() = cache.evictAll()
}
