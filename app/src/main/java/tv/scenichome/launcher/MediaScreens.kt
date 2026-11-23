package tv.scenichome.launcher

import android.graphics.Bitmap
import android.text.Html
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mediaShape = RoundedCornerShape(14.dp)
internal const val GOLD_EXPIRED = "No 30nama Gold days remaining"

@Composable
private fun CatalogImage(
    media: MediaStore,
    url: String?,
    modifier: Modifier,
    size: Int,
    scale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(null, url, size) {
        value = null
        value = media.artwork.image(url, size)
    }
    val image = bitmap
    if (image != null) Image(remember(image) { image.asImageBitmap() }, null, modifier, contentScale = scale)
    else Box(modifier.background(panel))
}

@Composable
private fun TitleCard(
    media: MediaStore,
    title: MediaTitle,
    id: String,
    move: FocusMove?,
    focused: (String) -> Unit,
    open: (MediaTitle) -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(id, move, focused, { open(title) }, modifier, fill = panel) {
        Column(Modifier.fillMaxWidth().clip(mediaShape)) {
            CatalogImage(media, title.cover ?: title.poster, Modifier.fillMaxWidth().height(91.dp), 420)
            Text(
                title.name, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(textDirection = TextDirection.Content),
            )
        }
    }
}

@Composable
internal fun MediaShelf(
    media: MediaStore,
    key: String,
    label: String,
    titles: List<MediaTitle>,
    move: FocusMove?,
    focused: (String) -> Unit,
    onTitle: (MediaTitle) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    val row = rememberLazyListState()
    LaunchedEffect(move) {
        val index = titles.indexOfFirst { "home/$key/${it.id}" == move?.id }
        if (index >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == index }) row.scrollToItem(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = foreground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (key == "watch" && media.account?.days?.let { it <= 0 } == true) {
                Text(GOLD_EXPIRED, color = Color(0xFFFFDCA6), fontSize = 15.sp)
            }
        }
        if (key == "watch" && !media.connected) {
            Action("Connect 30nama", onConnect, "home/watch/status", move, focused)
        } else if (key == "watch" && media.connectionIssue) {
            Action("Retry Watchlist", onRetry, "home/watch/status", move, focused)
        }
        if (titles.isEmpty() && media.connected && !media.connectionIssue) {
            Text(if (media.loading) "Loading Watchlist…" else "Save titles from search to see them here.",
                color = secondary, fontSize = 17.sp)
        }
        if (titles.isNotEmpty()) {
            LazyRow(
                state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(titles, key = MediaTitle::id) { title ->
                    TitleCard(media, title, "home/$key/${title.id}", move, focused, onTitle, Modifier.width(165.dp))
                }
            }
        }
    }
}

internal enum class SearchScope(val label: String, val group: String?) {
    CATALOG("30nama", null), WATCHLIST("Watchlist", "watchlist"), LIKED("Liked", "favorite"),
}

internal data class Lookup(val scope: SearchScope, val query: String, val filters: Map<String, String>, val revision: Int)

internal class MediaSearchState {
    var query by mutableStateOf("")
    private var selected by mutableStateOf(SearchScope.CATALOG)
    private var selectedFilters by mutableStateOf<Map<String, BrowseOption>>(emptyMap())
    private var loaded by mutableStateOf<Lookup?>(null)
    private val libraryPages = mutableMapOf<Pair<String, Int>, TitlePage>()
    private var libraryRevision = -1
    var titles by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var page by mutableIntStateOf(0)
        private set
    var pages by mutableIntStateOf(0)
        private set

    fun scope() = selected.label
    fun select(scope: SearchScope) { selected = scope }
    fun personal() = selected.group != null
    fun filter(key: String) = selectedFilters[key]
    fun setFilter(key: String, option: BrowseOption?) {
        selectedFilters = if (option == null) selectedFilters - key else selectedFilters + (key to option)
    }

    fun lookupKey(revision: Int) = Lookup(
        selected, query.trim(), if (query.isBlank() && !personal()) selectedFilters.mapValues { it.value.value } else emptyMap(),
        if (personal()) revision else 0,
    )
    fun matches(key: Lookup) = loaded == key

    fun clearPersonal() {
        libraryPages.clear()
        if (loaded?.scope?.group != null) {
            loaded = null
            titles = emptyList()
            page = 0
            pages = 0
        }
    }

    suspend fun load(media: MediaStore, key: Lookup, more: Boolean = false) {
        if (key.scope.group != null && libraryRevision != key.revision) {
            libraryPages.clear()
            libraryRevision = key.revision
        }
        val start = if (more && key == loaded) page + 1 else 1
        var accumulated = if (start > 1) titles else emptyList()
        var position = start
        do {
            val group = key.scope.group
            val found = if (group != null) {
                val cacheKey = group to position
                libraryPages[cacheKey] ?: media.list(group, position).also {
                    if (key.revision == media.listRevision) libraryPages[cacheKey] = it
                }
            } else media.search(key.query, key.filters, position)
            if (key != lookupKey(media.listRevision)) return
            val relevant = if (group != null && key.query.isNotBlank()) {
                found.titles.filter { matchesTitle(key.query, "${it.name} ${it.year}", it.localized) }
            } else found.titles
            accumulated = (accumulated + relevant).distinctBy(MediaTitle::id)
            loaded = key
            titles = accumulated
            page = position++
            pages = found.pages
        } while (key.scope.group != null && key.query.isNotBlank() && position <= pages)
    }
}

@Composable
internal fun MediaSearchScreen(
    activity: MainActivity,
    state: MediaSearchState,
    onConnect: () -> Unit,
    onTitle: (MediaTitle) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val media = activity.media
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var focused by rememberSaveable { mutableStateOf("search/input") }
    var move by remember { mutableStateOf(FocusMove(focused, 0)) }
    var chosenFilter by remember { mutableStateOf<String?>(null) }
    var filterOptions by remember { mutableStateOf<Map<String, List<BrowseOption>>>(emptyMap()) }
    var filterLoading by remember { mutableStateOf(false) }
    var filterFailed by remember { mutableStateOf(false) }
    val filterLabels = linkedMapOf("type" to "Type", "genre" to "Genre", "imdb_rate" to "IMDb rating")
    val request = state.lookupKey(media.listRevision)
    val searchable = media.configured && (!state.personal() || media.connected)

    LaunchedEffect(media.connected) { if (!media.connected) state.clearPersonal() }
    LaunchedEffect(request, searchable, retry) {
        if (!searchable || (retry == 0 && state.matches(request))) return@LaunchedEffect
        failed = false
        busy = true
        try {
            if (state.query.isNotBlank()) delay(320)
            grid.scrollToItem(0)
            failed = tryNama { state.load(media, request) }.isFailure
        } finally {
            busy = false
        }
    }
    LaunchedEffect(move) {
        withFrameNanos { }
        if (move?.id == "search/input") {
            field.requestFocus()
        } else {
            val resultIndex = state.titles.indexOfFirst { "search/title/${it.id}" == move?.id }
            if (resultIndex >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == resultIndex }) {
                grid.scrollToItem(resultIndex)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 26.dp)) {
        val columns = if (maxWidth >= 850.dp) 5 else 4
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Search", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = foreground, fontSize = 20.sp, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(mint),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f).focusRequester(field).onFocusChanged {
                        fieldFocused = it.isFocused
                        if (it.isFocused) focused = "search/input"
                    }.onPreviewKeyEvent {
                        val event = it.nativeKeyEvent
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            if (event.action == KeyEvent.ACTION_UP) keyboard?.show()
                            true
                        } else false
                    },
                    decorationBox = { edit ->
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).background(panel, mediaShape)
                                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) mint else edge, mediaShape)
                                .padding(horizontal = 15.dp), contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.query.isEmpty()) Text("Title or IMDb ID", color = secondary, fontSize = 19.sp)
                            edit()
                        }
                    },
                )
                if (state.query.isNotBlank()) {
                    Action("Search on YouTube", { activity.searchYouTube(state.query.trim()) }, "search/youtube", move, { focused = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                for (tab in SearchScope.entries) {
                    Choice(tab.label, tab.label == state.scope(), "search/scope/${tab.name}", move, { focused = it }) {
                        keyboard?.hide()
                        state.select(tab)
                    }
                }
            }
            if (media.configured && !state.personal() && state.query.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    for ((key, label) in filterLabels) {
                        val option = state.filter(key)
                        Choice(option?.label?.let { "$label: $it" } ?: label, option != null, "search/filter/$key", move, { focused = it }) {
                            chosenFilter = key
                            if (filterOptions.isEmpty() && !filterLoading) scope.launch {
                                filterLoading = true
                                filterFailed = false
                                filterOptions = tryNama { media.filters() }.getOrElse {
                                    filterFailed = true
                                    emptyMap()
                                }
                                filterLoading = false
                            }
                        }
                    }
                }
            }
            when {
                !media.configured -> Text("30nama search needs a compatible TV API connection.", color = secondary, fontSize = 18.sp)
                !searchable -> Action("Connect 30nama", onConnect, "search/connect", move, { focused = it })
                else -> {
                    val current = if (state.matches(request)) state.titles else emptyList()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 5.dp),
                    ) {
                        items(current, key = MediaTitle::id) { title ->
                            TitleCard(media, title, "search/title/${title.id}", move, { focused = it }, onTitle)
                        }
                        when {
                            busy || (!failed && !state.matches(request)) -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("Searching…", color = secondary, fontSize = 17.sp)
                            }
                            failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Action("Retry search", { retry++ }, "search/retry", move, { focused = it })
                            }
                            current.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (state.query.isBlank() && state.personal()) "${state.scope()} is empty." else "No titles found.",
                                    color = secondary, fontSize = 18.sp,
                                )
                            }
                            state.page < state.pages -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Action("More results", {
                                    scope.launch {
                                        busy = true
                                        val result = tryNama { state.load(media, request, more = true) }
                                        if (request == state.lookupKey(media.listRevision)) {
                                            failed = result.isFailure
                                            busy = false
                                        }
                                    }
                                }, "search/more", move, { focused = it })
                            }
                        }
                    }
                }
            }
        }
    }

    chosenFilter?.let { key ->
        val options = filterOptions[key].orEmpty()
        MediaDialog(filterLabels.getValue(key), when {
            filterLoading -> "Loading filters…"
            filterFailed -> "Couldn’t load filters. Close and try again."
            options.isEmpty() -> "30nama didn’t return any options."
            else -> null
        }, listOf("Any") + options.map(BrowseOption::label), close = {
            chosenFilter = null
            move = FocusMove("search/filter/$key", (move?.revision ?: 0) + 1)
        }) { choice ->
            state.setFilter(key, options.getOrNull(choice - 1))
            chosenFilter = null
            move = FocusMove("search/filter/$key", (move?.revision ?: 0) + 1)
        }
    }
}

@Composable
private fun Choice(label: String, chosen: Boolean, id: String, move: FocusMove?, focused: (String) -> Unit, click: () -> Unit) {
    val accent = if (chosen) mint else Color.Transparent
    Action(label, click, id, move, focused, Modifier.border(1.dp, accent, RoundedCornerShape(12.dp)))
}

@Composable
internal fun PairNamaScreen(activity: MainActivity, onClose: () -> Unit, onConnected: () -> Unit) {
    BackHandler(onBack = onClose)
    val media = activity.media
    var round by remember { mutableIntStateOf(0) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var link by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("Requesting a sign-in code…") }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(round) {
        if (!media.configured) return@LaunchedEffect
        qr = null
        link = ""
        hint = "Requesting a sign-in code…"
        finished = false
        val result = tryNama {
            val pairing = media.newPairing()
            qr = namaQr(pairing.url)
            link = pairing.url
            hint = "Scan this code with your phone. Sign in and approve the TV when prompted."
            media.approve(pairing)
        }
        if (result.getOrNull() == true) {
            onConnected()
        } else {
            val error = result.exceptionOrNull()
            hint = when {
                error is NamaFailure && error.browserChallenge ->
                    "30nama requested a browser check. Native sign-in is unavailable on this connection."
                error != null -> "Couldn’t connect to 30nama. Try a new code."
                else -> "This code timed out. Request a new code."
            }
            qr = null
            link = ""
            finished = true
        }
    }
    Row(
        Modifier.fillMaxSize().padding(46.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        qr?.let { bitmap ->
            Image(remember(bitmap) { bitmap.asImageBitmap() }, "30nama sign-in QR code", Modifier.size(210.dp))
        }
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect 30nama", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (media.configured) hint else "This build needs a compatible 30nama TV API connection.",
                fontSize = 19.sp, color = secondary,
            )
            if (link.isNotBlank()) Text(link, fontSize = 16.sp, color = foreground)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (finished) Action("New code", { round++ }, "pair/new", FocusMove("pair/new", round), {})
                Action("Back", onClose, "pair/back", if (!finished) FocusMove("pair/back", round) else null, {})
            }
        }
    }
}

private suspend fun namaQr(link: String): Bitmap = withContext(Dispatchers.Default) {
    val size = 384
    val matrix = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else -1 }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun MediaDialog(
    title: String,
    message: String?,
    options: List<String>,
    initial: Int = 0,
    close: () -> Unit,
    select: (Int) -> Unit,
) {
    val target = initial.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val list = rememberLazyListState(initialFirstVisibleItemIndex = target)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 510.dp).fillMaxWidth(.72f).heightIn(max = 425.dp)
                .background(panel, mediaShape).border(1.dp, edge, mediaShape).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, color = secondary, fontSize = 17.sp) }
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                items(options.size) { i ->
                    Action(options[i], { select(i) }, "dialog/$i", FocusMove("dialog/$target", 0), {}, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun MediaEpisode.label(series: Boolean): String {
    if (!series) return name.ifBlank { "Movie" }
    val part = if (number > 0) "Episode $number" else "Special"
    return if (name.isBlank()) part else "$part · $name"
}

@Composable
internal fun EpisodePicker(
    episodes: List<MediaEpisode>,
    selected: MediaEpisode?,
    onClose: () -> Unit,
    onSelect: (MediaEpisode) -> Unit,
) {
    val groups = episodes.groupBy(MediaEpisode::season).toSortedMap()
    val seasons = groups.keys.toList()
    var season by remember { mutableStateOf(selected?.season?.takeIf { it in groups }) }
    val current = if (seasons.size == 1) seasons.first() else season
    val choices = groups[current]?.sortedBy(MediaEpisode::number).orEmpty()
    if (current == null) {
        MediaDialog("Seasons", null, seasons.map { if (it == 0) "Specials" else "Season $it" },
            close = onClose) { season = seasons[it] }
    } else {
        val offset = if (seasons.size > 1) 1 else 0
        val actions = (if (offset == 1) listOf("Choose season") else emptyList()) + choices.map { it.label(true) }
        val initial = choices.indexOf(selected).coerceAtLeast(0) + offset
        androidx.compose.runtime.key(current) {
            MediaDialog(if (current == 0) "Specials" else "Season $current", null, actions, initial,
                close = onClose) { index ->
                if (index < offset) season = null else onSelect(choices[index - offset])
            }
        }
    }
}

@Composable
internal fun MediaTitleScreen(activity: MainActivity, original: MediaTitle, onConnect: () -> Unit, onClose: () -> Unit) {
    val media = activity.media
    val scope = rememberCoroutineScope()
    var title by remember(original.id) { mutableStateOf(original) }
    var catalog by remember { mutableStateOf<PlaybackCatalog?>(null) }
    var playing by remember { mutableStateOf<MediaEpisode?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var actionFocus by remember { mutableStateOf(FocusMove("title/play", 0)) }

    LaunchedEffect(original.id) { tryNama { media.detail(original) }.getOrNull()?.let { title = it } }
    val video = catalog
    val episode = playing
    if (video != null && episode != null) {
        MediaPlayerScreen(activity, title, video, episode, onEpisode = { playing = it }, onClose = {
            playing = null
            actionFocus = FocusMove("title/play", actionFocus.revision + 1)
        })
        return
    }

    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(ink)) {
        title.cover?.let { CatalogImage(media, it, Modifier.fillMaxSize(), 1600) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Color(0xFF0C201E), .62f to Color(0xF00C201E), 1f to Color(0x520C201E),
        )))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(25.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title.poster != null) CatalogImage(media, title.poster, Modifier.width(152.dp).height(228.dp).clip(mediaShape), 480)
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(title.name, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                    style = TextStyle(textDirection = TextDirection.Content))
                if (title.localized.isNotBlank()) Text(title.localized, fontSize = 19.sp, color = secondary)
                val facts = buildList {
                    if (title.year > 0) add(title.year.toString())
                    if (title.runtime > 0) add("${title.runtime} min")
                    if (title.series && title.seasons > 0) add("${title.seasons} seasons")
                    addAll(title.genres)
                }
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = secondary, fontSize = 17.sp)
                if (title.ratings.isNotEmpty()) Text(title.ratings.joinToString("   ·   "), color = Color(0xFFFFDCA6), fontSize = 16.sp)
                if (title.series && title.nextEpisode.isNotBlank()) Text(title.nextEpisode, color = secondary, fontSize = 16.sp)
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Play", {
                        when {
                            !media.configured -> onConnect()
                            !media.connected && !title.freeStream && !title.freeDownload -> onConnect()
                            else -> scope.launch {
                                actionFocus = FocusMove("title/play", actionFocus.revision + 1)
                                busy = true
                                notice = null
                                val data = tryNama { media.playback(title) }.getOrNull()
                                if (data == null || data.episodes.isEmpty()) {
                                    notice = if (media.account?.days?.let { it <= 0 } == true) GOLD_EXPIRED
                                        else "No playable source is available right now."
                                } else {
                                    catalog = data
                                    if (title.series) picking = true else playing = data.episodes.first()
                                }
                                busy = false
                            }
                        }
                    }, "title/play", actionFocus, {}, enabled = !busy)
                    for ((group, labels) in listOf("favorite" to ("Like" to "Remove like"),
                        "watchlist" to ("Add to Watchlist" to "Remove from Watchlist"))) {
                        val name = if (media.has(group, title.id)) labels.second else labels.first
                        Action(name, {
                            if (!media.connected || !media.configured) onConnect() else scope.launch {
                                if (!media.knows(group)) {
                                    notice = "Your lists are still unavailable. Try again in a moment."
                                    media.refresh()
                                    return@launch
                                }
                                busy = true
                                notice = null
                                if (!media.toggle(group, title.id)) notice = "Couldn’t confirm the change. Check your list and try again."
                                busy = false
                                actionFocus = FocusMove("title/$group", actionFocus.revision + 1)
                            }
                        }, "title/$group", actionFocus, {}, enabled = !busy)
                    }
                }
                if (busy) Text("Contacting 30nama…", color = secondary, fontSize = 16.sp)
                notice?.let { Text(it, color = Color(0xFFFFDCA6), fontSize = 17.sp) }
                if (title.description.isNotBlank()) {
                    Text(Html.fromHtml(title.description, Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                        fontSize = 18.sp, lineHeight = 26.sp, style = TextStyle(textDirection = TextDirection.Content))
                }
            }
        }
    }

    if (picking && video != null) {
        EpisodePicker(video.episodes, video.episodes.firstOrNull { it.fileId != 0 && it.fileId == video.resumeFile },
            onClose = { picking = false; actionFocus = FocusMove("title/play", actionFocus.revision + 1) }) {
            picking = false
            playing = it
        }
    }
}
