package tv.scenichome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class NamaSession(context: Context) {
    private val prefs = context.getSharedPreferences("30nama_connection", Context.MODE_PRIVATE)
    private val alias = "scenic-30nama-session"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
    }

    fun read(): String? = runCatching {
        val parts = prefs.getString("session", null)?.split('.') ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString()
    }.getOrNull()

    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.toByteArray())
        val encrypted = listOf(cipher.iv, data).joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        check(prefs.edit().putString("session", encrypted).commit())
    }

    fun clear() {
        check(prefs.edit().remove("session").commit())
    }
}

internal data class NamaPairing(val code: String, val url: String)
internal data class MediaWallpaper(val url: String, val bitmap: Bitmap, val frosted: Bitmap)

internal suspend fun <T> tryNama(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal class MediaStore(context: Context) {
    private val api = NamaApi()
    private val session = NamaSession(context)
    private val refreshLock = Mutex()
    private val sessionLock = Mutex()
    private var token: String? = null
    private var initialized = false

    val artwork = MediaArtwork()
    val configured get() = api.configured
    var connected by mutableStateOf(false)
        private set
    var account by mutableStateOf<MediaAccount?>(null)
        private set
    var watchlist by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var newReleases by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var suggestions by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var wallpaper by mutableStateOf<MediaWallpaper?>(null)
        private set
    var connectionIssue by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var listRevision by mutableIntStateOf(0)
        private set
    var accountRevision by mutableIntStateOf(0)
        private set
    private var lists by mutableStateOf<Map<String, Set<Int>>>(emptyMap())

    suspend fun initialize() = sessionLock.withLock {
        if (initialized) return@withLock
        token = withContext(Dispatchers.IO) { session.read() }
        connected = token != null
        initialized = true
    }

    private fun JSONObject.readAccount(): MediaAccount = MediaAccount(
        optJSONObject("user_expire")?.text("days")?.toIntOrNull(),
        optBoolean("user_allowed_to_stream"), optBoolean("user_allowed_to_download"),
    )

    suspend fun refresh() = refreshLock.withLock {
        initialize()
        if (!configured) return@withLock
        val current = token
        loading = true
        try {
            coroutineScope {
                val catalog = async { tryNama { api.call("home") } }
                val user = current?.let { async { tryNama { api.call("user", current).readAccount() } } }
                val rows = current?.let { async { tryNama { list("watchlist", 1, current) } } }
                val membership = current?.let { async { tryNama { api.call("user_mylist", current).readLists() } } }
                val home = catalog.await()
                home.getOrNull()?.let { result ->
                    newReleases = result.optJSONObject("new_releases")?.titlePage()?.titles.orEmpty()
                    suggestions = result.optJSONObject("suggested")?.titlePage()?.titles.orEmpty()
                }
                val currentAccount = user?.await()
                if (currentAccount?.exceptionOrNull().let { it is NamaFailure && it.rejectedSession } && current == token) {
                    forget(current)
                    return@coroutineScope
                }
                val updatedLists = membership?.await()
                val updatedRows = rows?.await()
                if (current == token) {
                    currentAccount?.getOrNull()?.let { account = it }
                    val memberships = updatedLists?.getOrNull()
                    val titles = updatedRows?.getOrNull()?.titles
                    if ((memberships != null && lists != memberships) || (titles != null && titles != watchlist)) {
                        listRevision++
                    }
                    if (memberships != null) {
                        lists = memberships
                    }
                    if (titles != null) watchlist = titles
                    connectionIssue = if (current == null) home.isFailure else
                        currentAccount?.isFailure == true || updatedRows?.isFailure == true || updatedLists?.isFailure == true
                }
            }
        } finally {
            loading = false
        }
    }

    private fun JSONObject.readLists() = listOf("watchlist", "favorite").associateWith { group ->
        val posts = optJSONObject(group)?.optJSONArray("post")
        if (posts == null) emptySet() else (0 until posts.length())
            .map { posts.optInt(it) }.filter { it > 0 }.toSet()
    }

    suspend fun refreshWallpaper() {
        val current = token
        val candidates = watchlist.mapNotNull(MediaTitle::cover).take(4)
        for (url in candidates) {
            if (current != token) return
            if (wallpaper?.url == url) return
            val bitmap = artwork.image(url, 1920) ?: continue
            if (bitmap.width < bitmap.height) continue
            val blur = withContext(Dispatchers.Default) { frostedWallpaper(bitmap) }
            if (current == token) wallpaper = MediaWallpaper(url, bitmap, blur)
            return
        }
        if (current == token && wallpaper?.url !in candidates) wallpaper = null
    }

    suspend fun newPairing(): NamaPairing {
        val result = api.call("qrlogin_code")
        val url = result.text("url")
        val code = result.text("code")
        if (httpsUri(url) == null || code.isBlank()) throw NamaFailure()
        return NamaPairing(code, url)
    }

    suspend fun approve(pairing: NamaPairing): Boolean {
        repeat(12) {
            delay(5_000)
            val issued = api.call("qrlogin_login", form = mapOf("auth_code" to pairing.code))
                .text("token").takeIf(String::isNotBlank) ?: return@repeat
            sessionLock.withLock {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { session.save(issued) }
                    clearAccount()
                    token = issued
                    connected = true
                }
            }
            return true
        }
        return false
    }

    private fun clearAccount() {
        account = null
        watchlist = emptyList()
        wallpaper = null
        lists = emptyMap()
        artwork.clear()
        listRevision++
        accountRevision++
    }

    private suspend fun forget(expected: String?) = sessionLock.withLock {
        if (token != expected) return@withLock
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { session.clear() }
            token = null
            connected = false
            connectionIssue = false
            clearAccount()
        }
    }

    suspend fun disconnect() {
        val current = token
        forget(current)
        if (current != null) tryNama { api.call("logout", current) }
    }

    fun has(group: String, id: Int): Boolean = id in lists[group].orEmpty()
    fun knows(group: String) = group in lists

    suspend fun toggle(group: String, id: Int): Boolean = refreshLock.withLock {
        val current = token ?: return@withLock false
        if (!knows(group)) return@withLock false
        val adding = !has(group, id)
        val response = tryNama {
            api.call("user_list", current, mapOf("action" to "add", "group" to group, "post_id" to id.toString()))
                .text("action")
        }.getOrNull()
        val confirmed = response == "add" || response == "remove"
        val updated = tryNama { api.call("user_mylist", current).readLists() }.getOrNull()
        if (current != token) return@withLock false
        lists = updated ?: if (confirmed) {
            val previous = lists[group].orEmpty()
            lists + (group to if (response == "add") previous + id else previous - id)
        } else lists
        if (group == "watchlist") {
            tryNama { list(group, 1, current) }.getOrNull()?.let { if (current == token) watchlist = it.titles }
        }
        if (current == token) listRevision++
        confirmed || (updated != null && has(group, id) == adding)
    }

    suspend fun list(group: String, page: Int, current: String? = token): TitlePage {
        if (current == null) return TitlePage(emptyList(), 1)
        return api.call("user_list", current, mapOf(
            "action" to "list", "group" to group, "page" to page.toString(),
            "orderby" to if (group == "watchlist") "update" else "add_list", "order" to "DESC",
        )).titlePage()
    }

    suspend fun search(query: String, filters: Map<String, String>, page: Int): TitlePage =
        if (query.isNotBlank()) {
            api.call("search", form = mapOf("query" to query, "streamonly" to "false", "page" to page.toString())).titlePage()
        } else {
            api.call("advanced_search", form = mapOf(
                "parameters" to browseParameters(filters), "orderby" to "update", "order" to "DESC", "page" to page.toString(),
            )).titlePage()
        }

    suspend fun filters(): Map<String, List<BrowseOption>> {
        val values = api.call("advanced_search_parameters")
        return listOf("type", "genre", "imdb_rate").associateWith { field ->
            values.optJSONArray(field).objects().mapNotNull {
                val value = it.text("value")
                val label = it.text("title").ifBlank { it.text("name") }
                if (value.isBlank() || label.isBlank()) null else BrowseOption(label, value)
            }
        }.filterValues { it.isNotEmpty() }
    }

    suspend fun detail(title: MediaTitle): MediaTitle =
        api.call("single", token, mapOf("post_id" to title.id.toString())).mediaTitle() ?: title

    private suspend fun download(title: MediaTitle): List<DownloadVideo> {
        if (account?.download != true && !title.freeDownload) return emptyList()
        return api.call("download", token, mapOf(
            "post_id" to title.id.toString(), "free_download" to (account?.download != true && title.freeDownload).toString(),
        )).downloads()
    }

    suspend fun playback(title: MediaTitle): PlaybackCatalog {
        val stream = if (account?.stream == true || title.freeStream) tryNama {
            val form = buildMap {
                put("post_id", title.id.toString())
                if (account?.stream != true && title.freeStream) put("free_stream", "true")
            }
            api.call("stream", token, form).playbackCatalog()
        }.getOrNull() else null
        if (stream != null && stream.episodes.isNotEmpty()) return stream
        val releases = download(title)
        val episodes = releases.distinctBy { it.season to it.episode }.map {
            MediaEpisode(0, it.season, it.episode, "", null, it.captions)
        }
        return PlaybackCatalog(episodes, 0, 0L, releases)
    }

    suspend fun alternatives(title: MediaTitle, catalog: PlaybackCatalog, episode: MediaEpisode): List<DownloadVideo> =
        (catalog.downloads ?: download(title)).filter {
            !title.series || (it.season == episode.season && it.episode == episode.number)
        }
}
