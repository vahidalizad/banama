package tv.scenichome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class Scene(
    val id: String,
    val title: String,
    val artist: String,
    val license: String,
    val source: String,
    val imageUrl: String,
) {
    companion object {
        val fallback = Scene(
            "murlough",
            "Murlough Bay",
            "Salvatore Gerace",
            "CC0",
            "https://commons.wikimedia.org/wiki/File:Murlough_bay_(48770060832).jpg",
            "",
        )

        fun fromJson(json: JSONObject) = Scene(
            json.getString("id"), json.getString("title"), json.getString("artist"),
            json.getString("license"), json.getString("source"), json.getString("image"),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("artist", artist)
        .put("license", license).put("source", source).put("image", imageUrl)
}

internal data class LoadedScene(val scene: Scene, val bitmap: Bitmap) {
    // The decoder creates one small blurred wallpaper for all Home controls.
    val frosted = frostedWallpaper(bitmap)
}

internal class PhotoGallery(private val context: Context) {
    private val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "scenes")

    fun restore(id: String): LoadedScene? {
        if (id == Scene.fallback.id) {
            val bitmap = context.resources.openRawResource(R.raw.murlough_bay)
                .use { decode(it.readBytes()) } ?: return null
            return LoadedScene(Scene.fallback, bitmap)
        }
        return readScenes("cache").firstOrNull { it.id == id }?.let(::readCached)
    }

    fun next(current: String): PhotoChoice<LoadedScene>? {
        val catalog = refreshCatalog()
        val cache = readScenes("cache")
        val cachedIds = cache.map { it.id }.toSet()
        val unseen = catalog.filter { it.id != current && it.id !in cachedIds }.shuffled().take(4)
        val candidates = (unseen + cache.filter { it.id != current }.shuffled()).associateBy { it.id }

        return firstUsablePhoto(candidates.keys, current) { id ->
            val scene = candidates.getValue(id)
            if (id in cachedIds) readCached(scene) else download(scene)
        } ?: if (current != Scene.fallback.id) {
            restore(Scene.fallback.id)?.let { PhotoChoice(it.scene.id, it) }
        } else null
    }

    private fun refreshCatalog(): List<Scene> {
        val existing = readScenes("catalog")
        val now = System.currentTimeMillis()
        val elapsed = now - prefs.getLong("last_attempt", 0)
        if (elapsed in 0 until DAILY_MILLIS) return existing
        prefs.edit().putLong("last_attempt", now).apply()

        val latest = listOf("Featured pictures of landscapes", "Featured pictures of cityscapes")
            .flatMap(::readCategory)
        val combined = (latest + existing).distinctBy { it.id }.take(72)
        writeScenes("catalog", combined)
        return combined
    }

    private fun readCategory(category: String): List<Scene> {
        val uri = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
        mapOf(
            "action" to "query", "generator" to "categorymembers", "gcmtitle" to "Category:$category",
            "gcmtype" to "file", "gcmsort" to "timestamp", "gcmdir" to "desc", "gcmlimit" to "24",
            "prop" to "imageinfo", "iiprop" to "url|size|mime|extmetadata",
            "iiextmetadatafilter" to "Artist|LicenseShortName",
            "iiurlwidth" to "1920", "format" to "json", "formatversion" to "2",
        ).forEach { (key, value) -> uri.appendQueryParameter(key, value) }
        val bytes = readHttps(uri.build().toString(), MAX_METADATA_BYTES) ?: return emptyList()
        val pages = runCatching { JSONObject(bytes.decodeToString()).optJSONObject("query")?.optJSONArray("pages") }
            .getOrNull() ?: return emptyList()
        return (0 until pages.length()).mapNotNull { index -> pages.optJSONObject(index)?.let(::parsePage) }
    }

    private fun parsePage(page: JSONObject): Scene? {
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val width = info.optInt("width")
        val height = info.optInt("height")
        if (width < 1280 || height <= 0 || width.toDouble() / height < 1.3 ||
            info.optString("mime") !in imageMimes
        ) return null

        val id = page.optLong("pageid")
        val image = sanitizedImageUrl(info.optString("thumburl")) ?: return null
        val source = info.optString("descriptionurl")
        if (id <= 0 || !source.startsWith("https://commons.wikimedia.org/wiki/File:")) return null
        val metadata = info.optJSONObject("extmetadata") ?: return null
        val license = metadata.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
        val rawArtist = metadata.optJSONObject("Artist")?.optString("value").orEmpty()
        val artist = Html.fromHtml(rawArtist, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim().take(200)
        if (license !in allowedLicenses || artist.isBlank()) return null

        val file = page.optString("title").removePrefix("File:").replace('_', ' ')
        val title = file.substringBeforeLast('.').trim().take(120)
        if (title.isBlank()) return null
        return Scene(id.toString(), title, artist, license, source, image)
    }

    private fun sanitizedImageUrl(value: String): String? {
        val url = Uri.parse(value)
        if (url.scheme != "https" || url.host !in imageHosts ||
            url.userInfo != null || (url.port != -1 && url.port != 443)
        ) return null
        return url.buildUpon().clearQuery().fragment(null).build().toString()
    }

    private fun download(scene: Scene): LoadedScene? {
        val url = sanitizedImageUrl(scene.imageUrl) ?: return null
        val bytes = readHttps(url, MAX_IMAGE_BYTES) ?: return null
        val bitmap = decode(bytes) ?: return null

        val cached = runCatching {
            directory.mkdirs()
            val file = fileFor(scene.id) ?: return null
            val temp = File(directory, "download.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) throw IOException("Couldn't cache photo")
            val keep = (listOf(scene) + readScenes("cache")).distinctBy { it.id }.take(6)
            writeScenes("cache", keep)
            val used = keep.map { "${it.id}.image" }.toSet()
            directory.listFiles()?.filter { it.extension == "image" && it.name !in used }?.forEach { it.delete() }
        }
        if (cached.isFailure) File(directory, "download.tmp").delete()
        return LoadedScene(scene, bitmap)
    }

    private fun readCached(scene: Scene): LoadedScene? = runCatching {
        val file = fileFor(scene.id) ?: return null
        if (!file.isFile || file.length() !in 1L..MAX_IMAGE_BYTES.toLong()) return null
        val bitmap = decode(file.readBytes()) ?: return null
        LoadedScene(scene, bitmap)
    }.getOrNull()

    private fun fileFor(id: String): File? =
        id.toLongOrNull()?.takeIf { it > 0 }?.let { File(directory, "$it.image") }

    private fun readScenes(key: String): List<Scene> {
        val json = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { index ->
            runCatching { Scene.fromJson(json.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun writeScenes(key: String, scenes: List<Scene>) {
        val json = JSONArray()
        scenes.forEach { json.put(it.toJson()) }
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1440) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val cropWidth = minOf(bitmap.width, bitmap.height * 16 / 9)
        val cropHeight = minOf(bitmap.height, bitmap.width * 9 / 16)
        if (cropWidth < 16 || cropHeight < 9) {
            bitmap.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(
            bitmap, (bitmap.width - cropWidth) / 2, (bitmap.height - cropHeight) / 2,
            cropWidth, cropHeight,
        )
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    private fun readHttps(address: String, limit: Int): ByteArray? = runCatching {
        val connection = URL(address).openConnection() as HttpsURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection.responseCode != 200 || connection.contentLengthLong > limit) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > limit) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val DAILY_MILLIS = 24 * 60 * 60 * 1000L
        const val USER_AGENT =
            "ScenicHomeForAndroidTV/1.0 (Personal launcher using Wikimedia Commons featured photography)"
        val imageHosts = setOf("thumb.wikimedia.org", "upload.wikimedia.org")
        val imageMimes = setOf("image/jpeg", "image/png", "image/webp")
        val allowedLicenses = setOf("CC0", "Public domain", "CC BY 4.0", "CC BY-SA 4.0")
    }
}
