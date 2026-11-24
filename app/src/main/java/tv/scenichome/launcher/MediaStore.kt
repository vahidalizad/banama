package tv.scenichome.launcher

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val watchedAt: Long,
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
        check(prefs.edit().putString("session", encrypted).remove("progress").remove("hiddenHeroTitles").commit())
    }

    fun clear() {
        check(prefs.edit().remove("session").remove("progress").remove("hiddenHeroTitles").commit())
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
                item.optLong("watchedAt"),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    fun hiddenHeroTitles(): Set<Int> = prefs.getStringSet("hiddenHeroTitles", null).orEmpty()
        .mapNotNull(String::toIntOrNull).toSet()

    fun saveProgress(progress: Map<Int, LocalProgress>, hiddenHeroTitles: Set<Int>) {
        val saved = JSONObject()
        progress.forEach { (id, value) ->
            saved.put(id.toString(), JSONObject().put("position", value.position.toJson())
                .put("furthestSeason", value.furthest.season).put("furthestEpisode", value.furthest.episode)
                .put("serverAtSave", value.serverAtSave?.toJson()).put("watchedAt", value.watchedAt))
        }
        prefs.edit().putString("progress", saved.toString())
            .putStringSet("hiddenHeroTitles", hiddenHeroTitles.map(Int::toString).toSet()).apply()
    }
}

internal data class NamaPairing(val code: String, val url: String)

internal suspend fun <T> tryNama(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal enum class MediaLibrary(val key: String, val label: String, val group: String, val order: String = "add_list") {
    WATCHLIST("watch", "Watchlist", "watchlist", "update"),
    NOTIFY("notify", "Notify", "notify"),
    LIKED("liked", "Liked", "favorite"),
}

private const val HOME_REFRESH_MS = 5 * 60_000L

internal class RefreshSchedule(
    home: Boolean,
    library: MediaLibrary?,
    signedIn: Boolean,
    private val nextCheck: Map<String, Long>,
) {
    private val keys = buildList {
        if (signedIn) {
            add("account")
            if (home) addAll(MediaLibrary.entries.map(MediaLibrary::key))
            else if (library != null) add(library.key)
        }
        if (home) add("home")
    }

    fun due(now: Long, force: Boolean = false) = keys.filter { force || (nextCheck[it] ?: 0L) <= now }

    fun delayFrom(now: Long): Long = keys.minOfOrNull { ((nextCheck[it] ?: 0L) - now).coerceAtLeast(1L) }
        ?: HOME_REFRESH_MS
}

private class UpcomingScan(val revision: Int) {
    val positions = mutableMapOf<Int, WatchPosition?>()
    val episodes = mutableMapOf<Int, List<UpcomingEpisode>>()
}

internal class MediaStore(context: Context) {
    private val api = NamaApi()
    private val session = NamaSession(context)
    private val refreshLock = Mutex()
    private val sessionLock = Mutex()
    private val progressLock = Mutex()
    private var token: String? = null
    private var initialized = false
    private val nextCheck = mutableMapOf<String, Long>()
    private var indexedRevision = 0
    private var pendingScan: UpcomingScan? = null
    private val titleStreams = RecentReads<Pair<Int, Boolean>, PlaybackCatalog>(1, 30_000)
    private val titleDownloads = RecentReads<Pair<Int, Boolean>, List<DownloadVideo>>(1, 30_000)

    val artwork = MediaArtwork()
    val configured get() = api.configured
    var connected by mutableStateOf(false)
        private set
    var account by mutableStateOf<MediaAccount?>(null)
        private set
    var shelves by mutableStateOf<Map<MediaLibrary, TitlePage>>(emptyMap())
        private set
    private val libraryRevisions = mutableStateMapOf<MediaLibrary, Int>()
    private val watchlist get() = shelves[MediaLibrary.WATCHLIST]?.titles.orEmpty()
    var suggestions by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var upcomingLoaded by mutableStateOf(false)
        private set
    private var connectionIssue by mutableStateOf(false)
    private var watchFailed by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    var homeLoaded by mutableStateOf(false)
        private set
    var accountRevision by mutableIntStateOf(0)
        private set
    var catalogRevision by mutableIntStateOf(0)
        private set
    private var lists by mutableStateOf<Map<String, Set<Int>>>(emptyMap())
    private var history by mutableStateOf<Map<Int, WatchedTitle>>(emptyMap())
    private var localProgress by mutableStateOf<Map<Int, LocalProgress>>(emptyMap())
    private var hiddenHeroTitles by mutableStateOf<Set<Int>>(emptySet())
    private var streamProgress by mutableStateOf<Map<Int, WatchPosition?>>(emptyMap())
    private var seriesEpisodes by mutableStateOf<Map<Int, List<UpcomingEpisode>>>(emptyMap())

    private suspend fun initialize() = sessionLock.withLock {
        if (initialized) return@withLock
        val (savedToken, progress, hidden) = withContext(Dispatchers.IO) {
            Triple(session.read(), session.progress(), session.hiddenHeroTitles())
        }
        token = savedToken
        if (savedToken != null) {
            localProgress = progress
            hiddenHeroTitles = hidden
        }
        connected = token != null
        initialized = true
    }

    private fun JSONObject.readAccount(): MediaAccount = MediaAccount(
        optJSONObject("user_expire")?.text("days")?.toIntOrNull(),
        optBoolean("user_allowed_to_stream"), optBoolean("user_allowed_to_download"), optInt("user_id"),
    )

    suspend fun refresh(home: Boolean, library: MediaLibrary? = null, force: Boolean = false): Long = refreshLock.withLock {
        initialize()
        if (!configured) return@withLock HOME_REFRESH_MS
        val current = token
        val schedule = RefreshSchedule(home, library, current != null, nextCheck)
        val now = SystemClock.elapsedRealtime()
        val due = schedule.due(now, force)
        fun checked(key: String) {
            if (current == token) nextCheck[key] = now + HOME_REFRESH_MS
        }

        if (due.isNotEmpty()) {
            loading = true
            try {
                coroutineScope {
                    if ("home" in due) launch {
                        tryNama { api.read("home") { it.optJSONObject("suggested")?.titlePage()?.titles.orEmpty() } }
                            .onSuccess { suggestions = it }
                        homeLoaded = true
                        checked("home")
                    }
                    if (current == null) return@coroutineScope
                    val accountCheck = if ("account" in due) launch {
                        refreshAccount(current)
                        checked("account")
                    } else null
                    suspend fun checkShelf(shelf: MediaLibrary) {
                        val result = tryNama { refreshShelf(shelf, current) }
                        accountCheck?.join()
                        if (current != token) return
                        if (shelf == MediaLibrary.WATCHLIST) {
                            watchFailed = result.isFailure
                            catalogRevision++
                        }
                        checked(shelf.key)
                    }
                    if (MediaLibrary.WATCHLIST.key in due) {
                        checkShelf(MediaLibrary.WATCHLIST)
                    }
                    for (shelf in MediaLibrary.entries) {
                        if (current == token && shelf != MediaLibrary.WATCHLIST && shelf.key in due) {
                            launch { checkShelf(shelf) }
                        }
                    }
                }
            } finally {
                loading = false
            }
        }
        schedule.delayFrom(SystemClock.elapsedRealtime())
    }

    private suspend fun refreshAccount(current: String) = coroutineScope {
        val resume = async { tryNama { api.read("continue_watching", current, decode = JSONObject::watchHistory) } }
        val user = async { tryNama { api.call("user", current).readAccount() } }
        val updatedHistory = resume.await().getOrNull()
        if (current == token && updatedHistory != null) {
            val changed = (history.keys + updatedHistory.keys).filter { history[it]?.position != updatedHistory[it]?.position }
            if (changed.isNotEmpty()) {
                streamProgress = streamProgress - changed.toSet()
                titleStreams.clear()
            }
            history = updatedHistory
        }
        val currentAccount = user.await()
        if (current != token) return@coroutineScope
        if ((currentAccount.exceptionOrNull() as? NamaFailure)?.rejectedSession == true) {
            forget(current)
            return@coroutineScope
        }
        currentAccount.getOrNull()?.let {
            if (account?.stream != it.stream || account?.download != it.download) clearPlaybackReads()
            account = it
        }
        val membership = tryNama { api.read("user_mylist", current) { it.readLists() } }
        if (current == token) {
            membership.getOrNull()?.let { updated ->
                for (library in MediaLibrary.entries) {
                    if (knows(library.group) && updated[library.group] != lists[library.group]) {
                        nextCheck.remove(library.key)
                        libraryRevisions[library] = libraryRevision(library) + 1
                    }
                }
                lists = updated
            }
            connectionIssue = currentAccount.isFailure || membership.isFailure
        }
    }

    private fun JSONObject.readLists() = listOf("watchlist", "favorite", "notify").associateWith { group ->
        val posts = optJSONObject(group)?.optJSONArray("post")
        if (posts == null) emptySet() else (0 until posts.length())
            .map { posts.optInt(it) }.filter { it > 0 }.toSet()
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
        shelves = emptyMap()
        libraryRevisions.clear()
        nextCheck.clear()
        clearPlaybackReads()
        indexedRevision = 0
        pendingScan = null
        catalogRevision = 0
        watchFailed = false
        upcomingLoaded = false
        lists = emptyMap()
        history = emptyMap()
        localProgress = emptyMap()
        hiddenHeroTitles = emptySet()
        streamProgress = emptyMap()
        seriesEpisodes = emptyMap()
        artwork.clear()
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
        val updated = if (confirmed) null else tryNama { api.read("user_mylist", current) { it.readLists() } }.getOrNull()
        if (current != token) return@withLock false
        lists = updated ?: if (confirmed) {
            val previous = lists[group].orEmpty()
            lists + (group to if (response == "add") previous + id else previous - id)
        } else lists
        val library = MediaLibrary.entries.first { it.group == group }
        if (confirmed || (updated != null && has(group, id) == adding)) {
            val revision = libraryRevision(library)
            val shelf = tryNama { refreshShelf(library, current) }
            if (current != token) return@withLock false
            if (shelf.isFailure) nextCheck.remove(library.key)
            if (revision == libraryRevision(library)) libraryRevisions[library] = revision + 1
            if (library == MediaLibrary.WATCHLIST) catalogRevision++
            true
        } else false
    }

    private suspend fun refreshShelf(library: MediaLibrary, current: String): TitlePage {
        val first = libraryPage(library, 1, current)
        if (current == token && first != shelves[library]) {
            shelves = shelves + (library to first)
            libraryRevisions[library] = libraryRevision(library) + 1
        }
        return first
    }

    suspend fun list(library: MediaLibrary, page: Int, retry: Boolean): TitlePage {
        val current = token ?: return TitlePage(emptyList(), 1)
        if (page != 1) return libraryPage(library, page, current)
        fun freshShelf() = shelves[library]?.takeIf {
            !retry && SystemClock.elapsedRealtime() < (nextCheck[library.key] ?: 0L)
        }
        freshShelf()?.let { return it }
        return refreshLock.withLock {
            if (current != token) throw NamaFailure()
            freshShelf() ?: refreshShelf(library, current).also {
                if (current != token) throw NamaFailure()
                nextCheck[library.key] = SystemClock.elapsedRealtime() + HOME_REFRESH_MS
                if (library == MediaLibrary.WATCHLIST) {
                    watchFailed = false
                    catalogRevision++
                }
            }
        }
    }

    fun libraryRevision(library: MediaLibrary) = libraryRevisions[library] ?: 0

    fun libraryRetry(library: MediaLibrary) = !loading &&
        (library !in shelves || library == MediaLibrary.WATCHLIST && (connectionIssue || watchFailed))

    private suspend fun libraryPage(library: MediaLibrary, page: Int, current: String): TitlePage =
        api.read("user_list", current, mapOf(
            "action" to "list", "group" to library.group, "page" to page.toString(),
            "orderby" to library.order, "order" to "DESC",
        ), JSONObject::titlePage)

    suspend fun search(query: String, page: Int): TitlePage =
        api.read("search", form = mapOf("query" to query, "streamonly" to "false", "page" to page.toString()), decode = JSONObject::titlePage)

    suspend fun browse(parameters: String, sort: SearchOrder, page: Int): TitlePage =
        api.read("advanced_search", form = mapOf(
            "parameters" to parameters, "orderby" to sort.parameter, "order" to "DESC", "page" to page.toString(),
        ), decode = JSONObject::titlePage)

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
        api.read("single", token, mapOf("post_id" to id.toString()), JSONObject::mediaTitle)

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

    private val upcoming: List<UpcomingEpisode>
        get() {
            val titles = watchlist.map(MediaTitle::id) + seriesEpisodes.keys.filter { has("watchlist", it) }
            return titles.distinct().mapNotNull { seriesEpisodes[it]?.nextAfter(watchedThrough(it)) }
        }

    private val resumeEpisodes: List<UpcomingEpisode>
        get() = history.values.map { UpcomingEpisode(it.title, lastPlayed(it.title.id)?.episode ?: it.position.episode) }

    val heroEpisodes: List<UpcomingEpisode>
        get() = orderedHeroEpisodes(resumeEpisodes, upcoming, localProgress.mapValues { it.value.watchedAt }, hiddenHeroTitles)

    suspend fun hideHeroTitle(titleId: Int, revision: Int) = sessionLock.withLock {
        if (token == null || revision != accountRevision) return@withLock
        hiddenHeroTitles = hiddenHeroTitles + titleId
        saveLocalProgress()
    }

    private suspend fun saveLocalProgress() {
        val progress = localProgress
        val hidden = hiddenHeroTitles
        withContext(NonCancellable + Dispatchers.IO) { session.saveProgress(progress, hidden) }
    }

    suspend fun refreshUpcoming() = coroutineScope {
        val current = token ?: return@coroutineScope
        val revision = catalogRevision
        if (revision == indexedRevision || SystemClock.elapsedRealtime() >= (nextCheck[MediaLibrary.WATCHLIST.key] ?: 0L)) {
            return@coroutineScope
        }
        val watchTitles = watchlist.toMutableList()
        for (page in 2..(shelves[MediaLibrary.WATCHLIST]?.pages ?: 1)) {
            if (current != token) return@coroutineScope
            val more = tryNama { libraryPage(MediaLibrary.WATCHLIST, page, current) }.getOrNull()?.titles.orEmpty()
            if (more.isEmpty()) break
            watchTitles.addAll(more)
        }
        val known = (watchTitles + history.values.map(WatchedTitle::title).filter { has("watchlist", it.id) })
            .distinctBy(MediaTitle::id)
        val typedIds = known.filter { it.type != TitleType.UNKNOWN }.mapTo(mutableSetOf(), MediaTitle::id)
        val missing = localProgress.keys.filter { has("watchlist", it) && it !in typedIds }
        val titles = known.filter(MediaTitle::series) + missing.mapNotNull {
            tryNama { detail(it) }.getOrNull()?.takeIf(MediaTitle::series)
        }
        val scan = pendingScan?.takeIf { it.revision == revision } ?: UpcomingScan(revision).also { pendingScan = it }
        for (batch in titles.filterNot { it.id in scan.episodes }.chunked(4)) {
            batch.map { title -> async {
                if (current != token) return@async
                val data = tryNama { stream(title, current) }
                val streamed = data.getOrNull()
                val downloads = if (current == token && (streamed?.savedPosition != null || watchedThrough(title.id) != null)) {
                    tryNama { download(title, current) }.getOrNull().orEmpty()
                } else emptyList()
                if (data.isSuccess || downloads.isNotEmpty()) {
                    val index = streamed ?: PlaybackCatalog(emptyList(), 0, 0L)
                    if (streamed != null) scan.positions[title.id] = streamed.savedPosition
                    scan.episodes[title.id] = index.includeDownloads(downloads).seriesEpisodes(title)
                }
            } }.forEach { it.await() }
        }
        if (current == token && revision == catalogRevision) {
            indexedRevision = revision
            pendingScan = null
            streamProgress = streamProgress + scan.positions
            seriesEpisodes = seriesEpisodes + scan.episodes
            upcomingLoaded = true
        }
    }

    suspend fun refreshTitlePosition(title: MediaTitle) {
        val current = token ?: return
        if (lastPlayed(title.id) != null || title.id in streamProgress) return
        val result = stream(title, current, reuse = true) ?: return
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
            localProgress = localProgress + (titleId to LocalProgress(position, furthest, serverPosition(titleId), System.currentTimeMillis()))
            hiddenHeroTitles = hiddenHeroTitles - titleId
            saveLocalProgress()
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

    fun clearPlaybackReads() {
        titleStreams.clear()
        titleDownloads.clear()
    }

    private suspend fun download(title: MediaTitle, current: String?, reuse: Boolean = false): List<DownloadVideo> {
        val free = account?.download != true
        if (free && !title.freeDownload) return emptyList()
        suspend fun read() = api.read("download", current, mapOf(
            "post_id" to title.id.toString(), "free_download" to free.toString(),
        ), JSONObject::downloads)
        return if (reuse) titleDownloads.get(title.id to free) { read() } else read()
    }

    private suspend fun stream(title: MediaTitle, current: String?, reuse: Boolean = false): PlaybackCatalog? {
        val free = account?.stream != true
        if (free && !title.freeStream) return null
        val form = buildMap {
            put("post_id", title.id.toString())
            if (free) put("free_stream", "true")
        }
        suspend fun read() = api.read("stream", current, form, JSONObject::playbackCatalog)
        return if (reuse) titleStreams.get(title.id to free) { read() } else read()
    }

    suspend fun playback(title: MediaTitle): PlaybackCatalog = coroutineScope {
        val current = token
        titleDownloads.clear()
        val seriesDownloads = if (title.series) async { tryNama { download(title, current, reuse = true) }.getOrNull().orEmpty() } else null
        val streamed = tryNama { stream(title, current, reuse = true) }.getOrNull()
        titleStreams.clear()
        val catalog = streamed ?: PlaybackCatalog(emptyList(), 0, 0L)
        val combined = when {
            seriesDownloads != null -> catalog.includeDownloads(seriesDownloads.await())
            catalog.episodes.isEmpty() -> catalog.includeDownloads(download(title, current, reuse = true))
            else -> catalog
        }
        if (current != token) throw NamaFailure()
        if (current != null) {
            if (streamed != null) streamProgress = streamProgress + (title.id to streamed.savedPosition)
            if (title.series && has("watchlist", title.id)) {
                seriesEpisodes = seriesEpisodes + (title.id to combined.seriesEpisodes(title))
            }
        }
        combined
    }

    suspend fun renewEpisode(title: MediaTitle, key: EpisodeNumber): MediaEpisode? {
        val current = token
        return stream(title, current)?.episodes?.firstOrNull { current == token && it.key == key }
    }

    suspend fun alternatives(title: MediaTitle, episode: MediaEpisode): List<DownloadVideo> =
        download(title, token, reuse = true).filter {
            !title.series || (it.season == episode.season && it.episode == episode.number)
        }
}
