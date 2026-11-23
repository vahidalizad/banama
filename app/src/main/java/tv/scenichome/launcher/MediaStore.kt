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

private data class LocalProgress(
    val position: WatchPosition,
    val furthest: EpisodeNumber,
    val serverAtSave: WatchPosition?,
)

private fun WatchPosition.toJson() = JSONObject().put("season", episode.season).put("episode", episode.episode)
    .put("timeMs", timeMs)

private fun JSONObject.toPosition() = WatchPosition(
    EpisodeNumber(optInt("season"), optInt("episode")), optLong("timeMs"),
)

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
        check(prefs.edit().putString("session", encrypted).remove("progress").commit())
    }

    fun clear() {
        check(prefs.edit().remove("session").remove("progress").commit())
    }

    fun progress(): Map<Int, LocalProgress> = runCatching {
        val saved = JSONObject(prefs.getString("progress", "{}").orEmpty())
        saved.keys().asSequence().mapNotNull { key ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val item = saved.getJSONObject(key)
            id to LocalProgress(
                item.getJSONObject("position").toPosition(),
                EpisodeNumber(item.optInt("furthestSeason"), item.optInt("furthestEpisode")),
                item.optJSONObject("serverAtSave")?.toPosition(),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    fun saveProgress(progress: Map<Int, LocalProgress>) {
        val saved = JSONObject()
        progress.forEach { (id, value) ->
            saved.put(id.toString(), JSONObject().put("position", value.position.toJson())
                .put("furthestSeason", value.furthest.season).put("furthestEpisode", value.furthest.episode)
                .put("serverAtSave", value.serverAtSave?.toJson()))
        }
        prefs.edit().putString("progress", saved.toString()).apply()
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
    private val progressLock = Mutex()
    private var token: String? = null
    private var initialized = false
    private var watchlistPages = 1

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
    var catalogRevision by mutableIntStateOf(0)
        private set
    private var lists by mutableStateOf<Map<String, Set<Int>>>(emptyMap())
    private var history by mutableStateOf<Map<Int, WatchedTitle>>(emptyMap())
    private var localProgress by mutableStateOf<Map<Int, LocalProgress>>(emptyMap())
    private var streamProgress by mutableStateOf<Map<Int, WatchPosition?>>(emptyMap())
    private var seriesEpisodes by mutableStateOf<Map<Int, List<UpcomingEpisode>>>(emptyMap())

    suspend fun initialize() = sessionLock.withLock {
        if (initialized) return@withLock
        val (savedToken, progress) = withContext(Dispatchers.IO) { session.read() to session.progress() }
        token = savedToken
        if (savedToken != null) localProgress = progress
        connected = token != null
        initialized = true
    }

    private fun JSONObject.readAccount(): MediaAccount = MediaAccount(
        optJSONObject("user_expire")?.text("days")?.toIntOrNull(),
        optBoolean("user_allowed_to_stream"), optBoolean("user_allowed_to_download"), optInt("user_id"),
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
                val resume = current?.let { async { tryNama { api.call("continue_watching", current).watchHistory() } } }
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
                val updatedHistory = resume?.await()
                if (current == token) {
                    currentAccount?.getOrNull()?.let { account = it }
                    val memberships = updatedLists?.getOrNull()
                    val watch = updatedRows?.getOrNull()
                    val titles = watch?.titles
                    if ((memberships != null && lists != memberships) || (titles != null && titles != watchlist)) {
                        listRevision++
                    }
                    if (memberships != null) {
                        lists = memberships
                    }
                    if (watch != null) {
                        watchlistPages = watch.pages
                        watchlist = watch.titles
                    }
                    updatedHistory?.getOrNull()?.let { latest ->
                        streamProgress = streamProgress - latest.keys.filter { latest[it]?.position != history[it]?.position }.toSet()
                        history = latest
                    }
                    catalogRevision++
                    connectionIssue = if (current == null) home.isFailure else
                        currentAccount?.isFailure == true || updatedRows?.isFailure == true || updatedLists?.isFailure == true
                }
            }
        } finally {
            loading = false
        }
    }

    private fun JSONObject.readLists() = listOf("watchlist", "favorite", "notify").associateWith { group ->
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
        watchlistPages = 1
        wallpaper = null
        lists = emptyMap()
        history = emptyMap()
        localProgress = emptyMap()
        streamProgress = emptyMap()
        seriesEpisodes = emptyMap()
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
            tryNama { list(group, 1, current) }.getOrNull()?.let {
                if (current == token) {
                    watchlistPages = it.pages
                    watchlist = it.titles
                }
            }
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

    suspend fun detail(title: MediaTitle): MediaTitle = detail(title.id) ?: title

    private suspend fun detail(id: Int): MediaTitle? =
        api.call("single", token, mapOf("post_id" to id.toString())).mediaTitle()

    private fun serverPosition(id: Int) = streamProgress[id] ?: history[id]?.position

    fun lastPlayed(id: Int): WatchPosition? {
        val server = serverPosition(id)
        val saved = localProgress[id] ?: return server
        return if (server == null || server == saved.serverAtSave ||
            (server.episode == saved.position.episode && kotlin.math.abs(server.timeMs - saved.position.timeMs) < 30_000L)
        ) saved.position else server
    }

    private fun watchedThrough(id: Int): EpisodeNumber? =
        listOfNotNull(serverPosition(id)?.episode, localProgress[id]?.furthest).maxOrNull()

    val upcoming: List<UpcomingEpisode>
        get() {
            val titles = watchlist.map(MediaTitle::id) + seriesEpisodes.keys.filter { has("watchlist", it) }
            return titles.distinct().mapNotNull { seriesEpisodes[it]?.nextAfter(watchedThrough(it)) }
        }

    suspend fun refreshUpcoming() = coroutineScope {
        val current = token ?: return@coroutineScope
        val watchTitles = watchlist.toMutableList()
        for (page in 2..watchlistPages) {
            if (current != token) return@coroutineScope
            val more = tryNama { list("watchlist", page, current) }.getOrNull()?.titles.orEmpty()
            if (more.isEmpty()) break
            watchTitles.addAll(more)
        }
        val candidates = (watchTitles + history.values.map(WatchedTitle::title).filter { has("watchlist", it.id) })
            .distinctBy(MediaTitle::id).filter(MediaTitle::series)
        val missing = localProgress.keys.filter { id -> has("watchlist", id) && candidates.none { it.id == id } }
        val titles = candidates + missing.mapNotNull { tryNama { detail(it) }.getOrNull()?.takeIf(MediaTitle::series) }
        val positions = mutableMapOf<Int, WatchPosition?>()
        val episodes = mutableMapOf<Int, List<UpcomingEpisode>>()
        for (batch in titles.chunked(4)) {
            batch.map { title -> async {
                if (current != token) return@async
                val data = tryNama { stream(title, current) }
                val streamed = data.getOrNull()
                val downloads = if (current == token && (streamed?.savedPosition != null || watchedThrough(title.id) != null)) {
                    tryNama { download(title, current) }.getOrNull().orEmpty()
                } else emptyList()
                if (data.isSuccess || downloads.isNotEmpty()) {
                    val index = streamed ?: PlaybackCatalog(emptyList(), 0, 0L)
                    if (streamed != null) positions[title.id] = streamed.savedPosition
                    episodes[title.id] = index.includeDownloads(downloads).seriesEpisodes(title)
                }
            } }.forEach { it.await() }
        }
        if (current == token) {
            streamProgress = streamProgress + positions
            seriesEpisodes = seriesEpisodes + episodes
        }
    }

    suspend fun refreshTitlePosition(title: MediaTitle) {
        val current = token ?: return
        if (lastPlayed(title.id) != null || title.id in streamProgress) return
        val result = stream(title, current) ?: return
        if (token == current) streamProgress = streamProgress + (title.id to result.savedPosition)
    }

    suspend fun recordProgress(
        titleId: Int,
        episode: MediaEpisode,
        timeMs: Long,
        durationMs: Long,
        revision: Int,
        report: Boolean,
    ) {
        val userId = account?.userId ?: 0
        val current = sessionLock.withLock {
            if (revision != accountRevision) return
            val sessionToken = token ?: return
            val position = WatchPosition(episode.key, timeMs)
            val furthest = watchedThrough(titleId)?.let { maxOf(it, episode.key) } ?: episode.key
            localProgress = localProgress + (titleId to LocalProgress(position, furthest, serverPosition(titleId)))
            session.saveProgress(localProgress)
            sessionToken
        }
        if (report && userId > 0 && episode.fileId > 0 && episode.progressToken.isNotBlank()) {
            progressLock.withLock {
                if (current != token || revision != accountRevision) return
                tryNama {
                    api.call("observer", current, mapOf(
                        "post_id" to titleId.toString(), "file_id" to episode.fileId.toString(),
                        "user_id" to userId.toString(), "time" to (timeMs / 1000).toString(),
                        "duration" to (durationMs / 1000).toString(), "token" to episode.progressToken,
                    ))
                }
            }
        }
    }

    private suspend fun download(title: MediaTitle, current: String? = token): List<DownloadVideo> {
        if (account?.download != true && !title.freeDownload) return emptyList()
        return api.call("download", current, mapOf(
            "post_id" to title.id.toString(), "free_download" to (account?.download != true && title.freeDownload).toString(),
        )).downloads()
    }

    private suspend fun stream(title: MediaTitle, current: String? = token): PlaybackCatalog? {
        if (account?.stream != true && !title.freeStream) return null
        val form = buildMap {
            put("post_id", title.id.toString())
            if (account?.stream != true && title.freeStream) put("free_stream", "true")
        }
        return api.call("stream", current, form).playbackCatalog()
    }

    suspend fun playback(title: MediaTitle): PlaybackCatalog = coroutineScope {
        val current = token
        val seriesDownloads = if (title.series) async { tryNama { download(title, current) }.getOrNull().orEmpty() } else null
        val streamed = tryNama { stream(title, current) }.getOrNull()
        val catalog = streamed ?: PlaybackCatalog(emptyList(), 0, 0L)
        val combined = when {
            seriesDownloads != null -> catalog.includeDownloads(seriesDownloads.await())
            catalog.episodes.isEmpty() -> catalog.includeDownloads(download(title, current))
            else -> catalog
        }
        if (current != null && current == token) {
            if (streamed != null) streamProgress = streamProgress + (title.id to streamed.savedPosition)
            if (title.series && has("watchlist", title.id)) {
                seriesEpisodes = seriesEpisodes + (title.id to combined.seriesEpisodes(title))
            }
        }
        combined
    }

    suspend fun alternatives(title: MediaTitle, episode: MediaEpisode): List<DownloadVideo> =
        download(title).filter {
            !title.series || (it.season == episode.season && it.episode == episode.number)
        }
}
