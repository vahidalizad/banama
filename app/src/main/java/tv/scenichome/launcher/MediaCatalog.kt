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
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal data class MediaTitle(
    val id: Int,
    val name: String,
    val localized: String,
    val year: Int,
    val series: Boolean,
    val poster: String?,
    val cover: String?,
    val description: String,
    val runtime: Int,
    val seasons: Int,
    val genres: List<String>,
    val ratings: List<String>,
    val nextEpisode: String,
    val freeStream: Boolean,
    val freeDownload: Boolean,
)

internal data class TitlePage(val titles: List<MediaTitle>, val pages: Int)
internal data class BrowseOption(val label: String, val value: String)
internal data class MediaAccount(val days: Int?, val stream: Boolean, val download: Boolean)
internal data class Caption(val language: String, val url: String)
internal data class MediaEpisode(
    val fileId: Int,
    val season: Int,
    val number: Int,
    val name: String,
    val hls: String?,
    val captions: List<Caption>,
)

internal data class DownloadVideo(val season: Int, val episode: Int, val url: String, val captions: List<Caption>)
internal data class PlaybackCatalog(
    val episodes: List<MediaEpisode>,
    val resumeFile: Int,
    val resumeMs: Long,
    val downloads: List<DownloadVideo>? = null,
)

internal fun isUhd(width: Int, height: Int) = width >= 3840 || height >= 2160

internal fun browseParameters(filters: Map<String, String>) = JSONObject().apply {
    for ((key, value) in filters) {
        put(key, if (key == "genre") JSONArray().put(value) else value)
    }
}.toString()

internal fun rangeAccepted(status: Int, range: String?, offset: Long): Boolean {
    val fields = range?.let { Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(it) } ?: return false
    val first = fields.groupValues[1].toLongOrNull() ?: return false
    val last = fields.groupValues[2].toLongOrNull() ?: return false
    val total = fields.groupValues[3].toLongOrNull()
    return status == 206 && first == offset && last == offset && (total == null || total > last)
}

internal fun matchesTitle(query: String, english: String, persian: String): Boolean {
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\p{M}\\u0640\\u200c]"), "")
    val text = normalize("$english $persian")
    return normalize(query).split(Regex("\\s+")).all { it in text }
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

    suspend fun call(action: String, session: String? = null, form: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            if (!configured) throw NamaFailure()
            val connection = httpsUri("$base/action/$action")!!.toURL().openConnection() as HttpsURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 9_000
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
                val result = connection.inputStream.use { JSONObject(it.readBounded(4 * 1024 * 1024).decodeToString()) }
                if (action != "qrlogin_login" && (!result.optBoolean("success") || result.optBoolean("error"))) {
                    throw NamaFailure(rejectedSession = result.optInt("status") == 401)
                }
                result.optJSONObject("result") ?: JSONObject()
            } finally {
                connection.disconnect()
            }
        }
}

internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONObject.art(key: String): String? =
    sequenceOf(text(key), optJSONObject(key)?.text("webp"), optJSONObject(key)?.text("jpg"))
        .firstOrNull { httpsUri(it) != null }

internal fun JSONObject.mediaTitle(): MediaTitle? {
    val id = optInt("id")
    val title = optJSONObject("title") ?: return null
    val name = title.text("english").ifBlank { title.text("local") }
    if (id <= 0 || name.isBlank()) return null
    val info = optJSONObject("info")
    val options = optJSONObject("options")
    val plot = optJSONObject("plot")
    val image = optJSONObject("image")
    val poster = image?.optJSONObject("poster")
    val posterUrl = sequenceOf(
        poster?.optJSONObject("webp")?.text("medium"), poster?.optJSONObject("jpg")?.text("medium"), poster?.text("preview"),
    ).firstOrNull { httpsUri(it) != null }
    val scores = optJSONObject("score")
    val ratings = listOf(
        "imdb" to "IMDb", "30nama" to "30nama", "rottentomatoes" to "Rotten Tomatoes",
        "metacritic" to "Metacritic", "mydramalist" to "MyDramaList", "myanimelist" to "MyAnimeList",
    ).mapNotNull { (key, label) ->
        val score = scores?.optJSONObject(key)?.text("score").orEmpty()
        score.takeIf { (it.removeSuffix("%").toDoubleOrNull() ?: 0.0) > 0 }?.let { "$label $it" }
    }
    return MediaTitle(
        id, name, title.text("local").takeUnless { it == name }.orEmpty(), info?.optInt("year") ?: 0,
        options?.optBoolean("is_series") == true || options?.text("title_type") == "series",
        posterUrl, image?.art("cover"), plot?.text("english")?.ifBlank { plot.text("local") }.orEmpty(),
        info?.optJSONObject("time")?.optInt("default") ?: 0, info?.optInt("seasons") ?: 0,
        optJSONArray("genre").objects().map { it.text("name") }.filter(String::isNotBlank), ratings,
        optJSONObject("air")?.text("next_episode_caption").orEmpty(),
        options?.optBoolean("free_stream") == true, options?.optBoolean("free_download") == true,
    )
}

internal fun JSONObject.titlePage() =
    TitlePage(optJSONArray("posts").objects().mapNotNull { it.mediaTitle() }.distinctBy(MediaTitle::id), optInt("pages", 1))

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
        MediaEpisode(
            info.optInt("file_id"), info.optInt("season"), info.optInt("episode_number", info.optInt("episode")),
            info.text("episode_name"), hls, captions,
        )
    }
    return PlaybackCatalog(videos, recent?.optInt("file_id") ?: 0, (recent?.optLong("time") ?: 0) * 1000)
}

private fun JSONObject.isUhdDownload() = Regex("""(?i)(2160|\b4k\b|\buhd\b)""").containsMatchIn(
    "${text("resolution")} ${text("quality")}",
) || Regex("""(?:3840|4096)\s*[x×]""").containsMatchIn(text("resolution"))

internal fun JSONObject.downloads(): List<DownloadVideo> {
    val bySeason = optJSONObject("download")
    val rows = optJSONArray("download").objects() + bySeason?.keys()?.asSequence()
        ?.flatMap { bySeason.optJSONArray(it).objects() }?.toList().orEmpty()
    return rows.filter { it.isUhdDownload() }.flatMap { row ->
        val season = row.optInt("season_int", row.optInt("season"))
        val links = row.optJSONArray("link").objects().ifEmpty { listOf(row) }
        links.mapNotNull { link ->
            val url = link.text("dl").takeIf { httpsUri(it) != null } ?: return@mapNotNull null
            val subtitle = link.text("persian_subtitle").ifBlank { row.text("persian_subtitle") }
            val captions = if (httpsUri(subtitle) != null) listOf(Caption("fa", subtitle)) else emptyList()
            DownloadVideo(season, link.optInt("episode"), url, captions)
        }
    }.distinctBy { it.url }
}

private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
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

internal suspend fun supportsByteSeeking(address: String): Boolean = withContext(Dispatchers.IO) {
    val uri = httpsUri(address) ?: return@withContext false
    runCatching {
        listOf(0L, 1_048_576L).all { offset ->
            readMedia(uri, offset) { connection ->
                rangeAccepted(connection.responseCode, connection.getHeaderField("Content-Range"), offset) &&
                    connection.inputStream.use { it.read() >= 0 && it.read() == -1 }
            } == true
        }
    }.getOrDefault(false)
}

internal class MediaArtwork {
    private val cache = object : LruCache<Pair<String, Int>, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, Int>, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun image(url: String?, size: Int): Bitmap? = withContext(Dispatchers.IO) {
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
