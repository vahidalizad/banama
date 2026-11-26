package tv.scenichome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val MAX_PHOTOS = 8
private const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
private const val MAX_FEED_BYTES = 512 * 1024
private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val RETRY_MS = 15 * 60 * 1000L
private const val SLIDE_MS = 60_000L
private const val COMMONS = "commons.wikimedia.org"
private val thumbnailHosts = setOf("thumb.wikimedia.org", "upload.wikimedia.org")
private val licenseHosts = setOf("creativecommons.org", "www.creativecommons.org")
private val feedUrl = "https://$COMMONS/w/api.php?action=query&generator=categorymembers" +
    "&gcmtitle=Category%3AFeatured%20pictures%20of%20landscapes&gcmtype=file&gcmlimit=24" +
    "&gcmsort=timestamp&gcmdir=desc&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata" +
    "&iiurlwidth=1280&iiextmetadatafilter=Artist%7CLicenseShortName%7CLicenseUrl&format=json&formatversion=2"

internal data class CommonsPhoto(
    val id: Long,
    val title: String,
    val artist: String,
    val license: String,
    val sourceUrl: String,
    val licenseUrl: String?,
    val thumbnail: String,
)

internal data class CommonsSlide(val photo: CommonsPhoto, val image: Bitmap)

internal fun commonsPhotos(json: String): List<CommonsPhoto> {
    val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
    return (0 until pages.length()).mapNotNull { i ->
        val page = pages.optJSONObject(i) ?: return@mapNotNull null
        val image = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
        val width = image.optInt("thumbwidth")
        val height = image.optInt("thumbheight")
        if (page.optInt("ns") != 6 || image.optString("mime") != "image/jpeg" ||
            width !in 1000..1280 || height < 500 || width.toDouble() / height !in 1.25..2.5
        ) return@mapNotNull null
        val meta = image.optJSONObject("extmetadata") ?: return@mapNotNull null
        readPhoto(JSONObject()
            .put("id", page.optLong("pageid"))
            .put("title", page.optString("title").removePrefix("File:"))
            .put("artist", meta.optJSONObject("Artist")?.optString("value"))
            .put("license", meta.optJSONObject("LicenseShortName")?.optString("value"))
            .put("licenseUrl", meta.optJSONObject("LicenseUrl")?.optString("value"))
            .put("sourceUrl", image.optString("descriptionurl"))
            .put("thumbnail", image.optString("thumburl")))
    }.distinctBy(CommonsPhoto::id)
}

private fun readPhoto(json: JSONObject): CommonsPhoto? {
    val id = json.optLong("id")
    val title = json.optString("title").trim()
    val artist = json.optString("artist").trim()
    val license = json.optString("license")
    val licenseUrl = httpsUrl(json.optString("licenseUrl"), licenseHosts)
    val source = httpsUrl(json.optString("sourceUrl"), setOf(COMMONS)) ?: return null
    val thumbnail = httpsUrl(json.optString("thumbnail"), thumbnailHosts) ?: return null
    val licensed = (license.startsWith("CC BY ") || license.startsWith("CC BY-SA ")) && licenseUrl != null
    if (id <= 0 || title.isEmpty() || artist.isEmpty() ||
        !(licensed || license in setOf("CC0", "Public domain"))
    ) return null
    return CommonsPhoto(id, title, artist, license, source, licenseUrl, thumbnail)
}

private fun httpsUrl(raw: String, hosts: Set<String>): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    return raw.takeIf { uri.scheme == "https" && uri.host in hosts && uri.userInfo == null && uri.port in setOf(-1, 443) }
}

internal class CommonsPhotos(context: Context) {
    private val directory = context.filesDir.resolve("commons-photos")
    private val index = AtomicFile(directory.resolve("photos.json"))
    private val prefs = context.getSharedPreferences("commons_photos", Context.MODE_PRIVATE)

    private fun imageFile(photo: CommonsPhoto) = directory.resolve("${photo.id}.jpg")

    fun cached(): List<CommonsPhoto> = try {
        val photos = JSONArray(index.readFully().toString(Charsets.UTF_8))
        (0 until photos.length()).mapNotNull { i -> photos.optJSONObject(i)?.let(::readPhoto) }
            .filter { imageFile(it).isFile }
    } catch (_: IOException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    fun slide(photo: CommonsPhoto): CommonsSlide? =
        BitmapFactory.decodeFile(imageFile(photo).path)?.let { CommonsSlide(photo, it) }

    @Synchronized
    fun refresh(): List<CommonsPhoto> {
        val saved = cached()
        val now = System.currentTimeMillis()
        if (saved.isNotEmpty() && now - index.baseFile.lastModified() in 0 until DAY_MS) return saved
        if (now - prefs.getLong("last_attempt", 0) in 0 until RETRY_MS) return saved
        prefs.edit().putLong("last_attempt", now).apply()
        val latest = try {
            commonsPhotos(download(feedUrl, MAX_FEED_BYTES).toString(Charsets.UTF_8))
        } catch (_: IOException) {
            return saved
        } catch (_: JSONException) {
            return saved
        }

        directory.mkdirs()
        val selected = latest.asSequence().filter { cacheImage(it) }.take(MAX_PHOTOS).toList()
        if (selected.size < saved.size || selected.isEmpty()) {
            prune(saved)
            return saved
        }
        val metadata = JSONArray()
        selected.forEach {
            metadata.put(JSONObject()
                .put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("license", it.license).put("sourceUrl", it.sourceUrl).put("licenseUrl", it.licenseUrl)
                .put("thumbnail", it.thumbnail))
        }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = index.startWrite()
            stream.write(metadata.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (_: IOException) {
            index.failWrite(stream)
            prune(saved)
            return saved
        }
        prune(selected)
        return selected
    }

    private fun cacheImage(photo: CommonsPhoto): Boolean {
        val file = imageFile(photo)
        if (file.isFile) return true
        val writer = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        return try {
            val bytes = download(photo.thumbnail, MAX_PHOTO_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1000..1280 || bounds.outHeight !in 500..1024) return false
            stream = writer.startWrite()
            stream.write(bytes)
            writer.finishWrite(stream)
            true
        } catch (_: IOException) {
            writer.failWrite(stream)
            false
        }
    }

    private fun prune(keep: List<CommonsPhoto>) {
        val files = keep.map(::imageFile).toSet()
        directory.listFiles()?.filter { it.extension == "jpg" && it !in files }?.forEach(File::delete)
    }
}

private fun download(url: String, limit: Int): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 8_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent",
        "BananaLauncher/${BuildConfig.VERSION_NAME} (https://github.com/vahidalizad/my-tv-launcher)")
    try {
        if (connection.responseCode != 200) throw IOException("Wikimedia returned ${connection.responseCode}")
        return connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw IOException("Wikimedia response is too large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun commonsSlide(activity: MainActivity, visible: Boolean): CommonsSlide? {
    val source = activity.photos
    val active = activity.foreground && visible
    val photos by produceState(emptyList<CommonsPhoto>(), active) {
        if (!active) return@produceState
        value = withContext(Dispatchers.IO) { source.cached() }
        while (true) {
            value = withContext(Dispatchers.IO) { source.refresh() }
            delay(RETRY_MS)
        }
    }
    var slide by remember { mutableStateOf<CommonsSlide?>(null) }
    LaunchedEffect(photos, active) {
        if (!active || photos.isEmpty()) return@LaunchedEffect
        var index = photos.indexOf(slide?.photo).coerceAtLeast(0)
        while (true) {
            val photo = photos[index]
            if (slide?.photo != photo) {
                withContext(Dispatchers.IO) { source.slide(photo) }?.let { slide = it }
            }
            delay(SLIDE_MS)
            index = (index + 1) % photos.size
        }
    }
    return slide
}
