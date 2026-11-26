package tv.scenichome.launcher

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.Html
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Year
import java.util.Locale

private val mediaShape = RoundedCornerShape(14.dp)
internal const val GOLD_EXPIRED = "No 30nama Gold days remaining"

@Composable
private fun CatalogImage(
    media: MediaStore,
    url: String?,
    modifier: Modifier,
    size: Int,
    scale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    val bitmap by produceState<Bitmap?>(null, url, size) {
        value = null
        value = media.artwork.image(url, size)
    }
    val image = bitmap
    if (image != null) Image(remember(image) { image.asImageBitmap() }, null, modifier, contentScale = scale)
    else Box(modifier.background(panel), contentAlignment = Alignment.Center) { placeholder() }
}

@Composable
internal fun EpisodeHero(
    media: MediaStore,
    episodes: List<UpcomingEpisode>,
    selected: UpcomingEpisode,
    move: FocusMove?,
    focused: (UpcomingEpisode) -> Unit,
    open: (UpcomingEpisode) -> Unit,
    options: (UpcomingEpisode) -> Unit,
    showOptionsHint: Boolean,
) {
    val row = rememberLazyListState()
    LaunchedEffect(move) {
        val index = episodes.indexOfFirst { heroKey(it.title.id) == move?.id }
        if (index >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == index }) row.scrollToItem(index)
    }
    Column(
        Modifier.widthIn(max = 696.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(selected.title.name, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val resume = media.lastPlayed(selected.title.id)?.takeIf { it.episode == selected.episode }
        val caption = resume?.let {
            listOfNotNull(
                selected.episode.label().takeIf { selected.title.series },
                "Resume ${clockTime(it.timeMs / 1000)}",
            ).joinToString(" · ")
        } ?: selected.episode.label()
        Text(if (showOptionsHint) "$caption · Hold OK for options" else caption, color = foreground, fontSize = 14.sp)
        LazyRow(
            state = row, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusGroup(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(episodes, key = { it.title.id }) { episode ->
                val active = selected.title.id == episode.title.id
                var hasFocus by remember { mutableStateOf(false) }
                val label = "Open ${episode.title.name}" +
                    (if (episode.title.series) " · ${episode.episode.label()}" else "")
                val selectedBorder = if (active && !hasFocus) Modifier.border(1.dp, Color(0xBFFFFFFF), RoundedCornerShape(12.dp))
                    else Modifier
                FocusSurface(
                    heroKey(episode.title.id), move, { focused(episode) }, { open(episode) },
                    Modifier.size(106.dp, 60.dp).then(selectedBorder).onFocusChanged { hasFocus = it.isFocused }
                        .semantics { contentDescription = label },
                    onLongClick = { options(episode) },
                    fill = panel,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        CatalogImage(media, episode.title.cover ?: episode.title.poster, Modifier.matchParentSize(), 260)
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (active) .17f else .32f)))
                    }
                }
            }
        }
    }
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
    var hasFocus by remember(title.id) { mutableStateOf(false) }
    FocusSurface(id, move, focused, { open(title) },
        modifier.onFocusChanged { hasFocus = it.isFocused }, fill = Color.Transparent) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .then(if (hasFocus) Modifier.border(2.dp, focusColor, tileShape) else Modifier)) {
            CatalogImage(media, title.cover ?: title.poster, Modifier.matchParentSize(), 420)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color(0x3D202020), .55f to Color(0x52202020), .83f to Color(0xB8000000),
                1f to Color(0xD6000000),
            )))
            Text(
                title.name,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
                    .then(if (hasFocus) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 650) else Modifier),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, overflow = if (hasFocus) TextOverflow.Clip else TextOverflow.Ellipsis,
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
    library: MediaLibrary?,
    onSeeAll: () -> Unit,
) {
    val row = rememberLazyListState()
    val ready = library != null && library in media.shelves
    val allId = "home/$key/all"
    LaunchedEffect(move) {
        val index = if (move?.id == allId && ready) titles.size
            else titles.indexOfFirst { "home/$key/${it.id}" == move?.id }
        if (index >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == index }) row.scrollToItem(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = foreground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (library == MediaLibrary.WATCHLIST && media.account?.days?.let { it <= 0 } == true) {
                Text(GOLD_EXPIRED, color = Color(0xFFFFDCA6), fontSize = 15.sp)
            }
        }
        when {
            library == MediaLibrary.WATCHLIST && !media.connected ->
                Action("Connect 30nama", onConnect, "home/$key/status", move, focused)
            library != null && media.libraryRetry(library) ->
                Action("Retry $label", onRetry, "home/$key/status", move, focused)
            library != null && !ready ->
                Text("Loading $label…", color = secondary, fontSize = 17.sp)
            ready && titles.isEmpty() ->
                Text("$label is empty.", color = secondary, fontSize = 17.sp)
        }
        if (titles.isNotEmpty() || ready) {
            LazyRow(
                state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(titles, key = MediaTitle::id) { title ->
                    TitleCard(media, title, "home/$key/${title.id}", move, focused, onTitle, Modifier.width(165.dp))
                }
                if (ready) item(key = allId) {
                    FocusSurface(allId, move, focused, onSeeAll, Modifier.width(165.dp).aspectRatio(16f / 9f)) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.media_library), null, Modifier.size(20.dp),
                                colorFilter = ColorFilter.tint(LocalContentColor.current))
                            Text("See all", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

internal data class Lookup(
    val library: MediaLibrary?,
    val query: String,
    val genres: Set<BrowseOption>,
    val years: Set<Int>,
    val type: String?,
    val rating: String?,
    val order: SearchOrder,
    val revision: Int,
) {
    val browse get() = library == null && query.isBlank()

    suspend fun readPage(page: Int, recent: RecentReads<Pair<String, Int>, TitlePage>, read: suspend () -> TitlePage): TitlePage {
        if (browse || page > SEARCH_PAGE_LIMIT) return read()
        val serverQuery = if (library == null) query else ""
        return recent.get(serverQuery to page, read)
    }
}

private enum class SearchControl(val label: String, val icon: Int, val apiKey: String? = null) {
    GENRE("Genres", R.drawable.media_filter, "genre"),
    YEAR("Years", R.drawable.media_calendar),
    SORT("Sort", R.drawable.media_sort),
    TYPE("Type", R.drawable.media_episodes, "type"),
    RATING("IMDb rating", R.drawable.media_star, "imdb_rate"),
}

private const val SEARCH_PAGE_LIMIT = 8
private const val SEARCH_REUSE_MS = 60_000L

internal class MediaSearchState(val library: MediaLibrary? = null) {
    val input = TextFieldState()
    val query get() = input.text.toString()
    var genres by mutableStateOf<Set<BrowseOption>>(emptySet())
    var years by mutableStateOf<Set<Int>>(emptySet())
    var type by mutableStateOf<BrowseOption?>(null)
    var rating by mutableStateOf<BrowseOption?>(null)
    var order by mutableStateOf(SearchOrder.DEFAULT)
    var options by mutableStateOf<Map<String, List<BrowseOption>>>(emptyMap())
    private var loaded by mutableStateOf<Lookup?>(null)
    private var loadedAt = 0L
    var titles by mutableStateOf<List<MediaTitle>>(emptyList())
        private set
    var page by mutableIntStateOf(0)
        private set
    var pages by mutableIntStateOf(0)
        private set

    fun lookupKey(media: MediaStore) = Lookup(library, query.trim(), genres, years,
        type?.value?.takeIf { library == null && query.isBlank() }, rating?.value?.takeIf { library == null && query.isBlank() },
        order, library?.let(media::libraryRevision) ?: 0)
    fun matches(key: Lookup) = loaded == key

    fun fresh(key: Lookup) = matches(key) && SystemClock.elapsedRealtime() - loadedAt < SEARCH_REUSE_MS

    suspend fun load(
        media: MediaStore,
        key: Lookup,
        recent: RecentReads<Pair<String, Int>, TitlePage>,
        more: Boolean = false,
        retry: Boolean = false,
    ) {
        val start = if (more && key == loaded) page + 1 else 1
        val through = if (!more && matches(key)) page else start
        val previous = if (start > 1) titles else emptyList()
        val accumulated = withContext(Dispatchers.Default) { previous.associateByTo(linkedMapOf(), MediaTitle::id) }
        val clientSort = !key.browse && key.order != SearchOrder.DEFAULT
        val parameters = if (key.browse) browseParameters(
            key.type, key.genres.map(BrowseOption::value).toSet(), key.years, key.rating, key.order,
        ) else ""
        var position = start
        var lastPage: Int
        do {
            val found = key.readPage(position, recent) {
                when {
                    key.library != null -> media.list(key.library, position, retry)
                    key.browse -> media.browse(parameters, key.order, position)
                    else -> media.search(key.query, position)
                }
            }
            if (key != lookupKey(media)) return
            val sizeBefore = accumulated.size
            // shortcut: sparse release years can skip server pages, use a year-set filter when the API supports one.
            withContext(Dispatchers.Default) {
                for (title in found.titles) {
                    if (title.matchesRelease(key.years) && (key.browse || title.matchesGenre(key.genres)) &&
                        (key.library == null || title.matchesLibraryQuery(key.query))) {
                        accumulated.putIfAbsent(title.id, title)
                    }
                }
            }
            lastPage = found.pages
            position++
        } while (position <= lastPage && (clientSort || position <= through || accumulated.size == sizeBefore))
        val result = withContext(Dispatchers.Default) {
            accumulated.values.toList().let { if (clientSort) key.order.sort(it) else it }
        }
        if (key != lookupKey(media)) return
        loaded = key
        if (start == 1) loadedAt = SystemClock.elapsedRealtime()
        titles = result
        page = position - 1
        pages = lastPage
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaSearchScreen(
    activity: MainActivity,
    state: MediaSearchState,
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
    var keyboardRequested by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf("search/input") }
    var move by remember { mutableStateOf(FocusMove(focused, 0)) }
    var chosenFilter by remember { mutableStateOf<SearchControl?>(null) }
    var filterLoading by remember { mutableStateOf(false) }
    var filterFailed by remember { mutableStateOf(false) }
    val request = state.lookupKey(media)
    var retry by remember(request) { mutableIntStateOf(0) }
    var submittedQuery by remember(state) { mutableStateOf<String?>(null) }
    val recent = remember(state, request.revision, retry, media.accountRevision) {
        RecentReads<Pair<String, Int>, TitlePage>(SEARCH_PAGE_LIMIT, SEARCH_REUSE_MS)
    }
    val searchable = media.configured && (state.library == null || media.connected)

    fun leaveSearchInput(goToResult: Boolean) {
        keyboard?.hide()
        val result = state.titles.firstOrNull()?.takeIf { goToResult && state.query.isNotBlank() && state.matches(request) }
        val id = if (result != null) "search/title/${result.id}" else "search/filter/${SearchControl.GENRE.name}"
        move = FocusMove(id, (move?.revision ?: 0) + 1)
    }

    fun closeFilter() {
        val previous = chosenFilter ?: return
        chosenFilter = null
        move = FocusMove("search/filter/${previous.name}", (move?.revision ?: 0) + 1)
    }

    LaunchedEffect(chosenFilter) {
        if (chosenFilter?.apiKey == null || state.options.isNotEmpty()) return@LaunchedEffect
        filterLoading = true
        filterFailed = false
        try {
            state.options = tryNama { media.filters() }.getOrElse {
                filterFailed = true
                emptyMap()
            }
        } finally {
            filterLoading = false
        }
    }

    LaunchedEffect(request, searchable, retry, activity.foreground) {
        if (!activity.foreground || !searchable || (retry == 0 && state.fresh(request))) return@LaunchedEffect
        val reloading = state.matches(request)
        if (reloading) recent.clear()
        failed = false
        busy = true
        try {
            if (request.query.isNotBlank() && request.query != submittedQuery) delay(320)
            submittedQuery = request.query
            if (!reloading) grid.scrollToItem(0)
            failed = tryNama { state.load(media, request, recent, retry = retry > 0) }.isFailure
            if (!failed && focused.startsWith("search/title/") && state.titles.none { "search/title/${it.id}" == focused }) {
                val next = state.titles.firstOrNull()?.let { "search/title/${it.id}" } ?: "search/input"
                move = FocusMove(next, (move?.revision ?: 0) + 1)
            }
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
                state.library?.let { Text(it.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                BasicTextField(
                    state = state.input,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = TextStyle(color = foreground, fontSize = 20.sp, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(focusColor),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search, showKeyboardOnFocus = keyboardRequested,
                    ),
                    onKeyboardAction = { leaveSearchInput(goToResult = true) },
                    modifier = Modifier.weight(1f).focusRequester(field).onFocusChanged {
                        fieldFocused = it.isFocused
                        if (it.isFocused) focused = "search/input"
                        else keyboardRequested = false
                    }.onPreviewKeyEvent {
                        val event = it.nativeKeyEvent
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER -> {
                                if (event.action == KeyEvent.ACTION_UP) {
                                    keyboardRequested = true
                                    keyboard?.show()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leaveSearchInput(goToResult = false)
                                true
                            }
                            else -> false
                        }
                    },
                    decorator = { edit ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).background(panel, mediaShape)
                                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) focusColor else edge, mediaShape)
                                .padding(horizontal = 15.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.media_search), null, Modifier.size(21.dp))
                            Box(Modifier.weight(1f)) {
                                if (state.query.isEmpty()) Text(
                                    if (state.library == null) "Search by title or IMDb ID" else "Search this list",
                                    color = secondary, fontSize = 19.sp,
                                )
                                edit()
                            }
                        }
                    },
                )
                if (state.library == null && state.query.isNotBlank()) {
                    Action("YouTube", { activity.searchYouTube(state.query.trim()) }, "search/youtube", move,
                        { focused = it }, Modifier.semantics { contentDescription = "Search on YouTube" },
                        icon = R.drawable.media_youtube)
                }
            }
            if (searchable) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (control in SearchControl.entries) {
                        if (!request.browse && control in listOf(SearchControl.TYPE, SearchControl.RATING)) continue
                        val active = when (control) {
                            SearchControl.GENRE -> state.genres.size.takeIf { it > 0 }?.let { "Genres · $it" }
                            SearchControl.YEAR -> state.years.size.takeIf { it > 0 }?.let { "Years · $it" }
                            SearchControl.SORT -> when (state.order) {
                                SearchOrder.DEFAULT -> null
                                SearchOrder.RELEASE -> "Newest"
                                SearchOrder.RANK -> state.order.label
                            }
                            SearchControl.TYPE -> state.type?.label
                            SearchControl.RATING -> state.rating?.label?.let { "IMDb $it" }
                        }
                        FilterChip(active ?: control.label, active != null, "search/filter/${control.name}",
                            move, { focused = it }, control.icon) {
                            keyboard?.hide()
                            chosenFilter = control
                        }
                    }
                }
            }
            when {
                !media.configured -> Text("30nama search needs a compatible TV API connection.", color = secondary, fontSize = 18.sp)
                !searchable -> Text("Connect 30nama from the Watchlist on Home.", color = secondary, fontSize = 18.sp)
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
                            !state.matches(request) && (busy || !failed) -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("Searching…", color = secondary, fontSize = 17.sp)
                            }
                            failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Action("Retry search", { retry++ }, "search/retry", move, { focused = it })
                            }
                            current.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "No titles found.",
                                    color = secondary, fontSize = 18.sp,
                                )
                            }
                            state.page < state.pages -> item(span = { GridItemSpan(maxLineSpan) }) {
                                Action(if (busy) "Loading more…" else "More results", {
                                    if (busy) return@Action
                                    busy = true
                                    scope.launch {
                                        val result = tryNama { state.load(media, request, recent, more = true) }
                                        if (request == state.lookupKey(media)) {
                                            failed = result.isFailure
                                            busy = false
                                            val next = state.titles.getOrNull(current.size) ?: state.titles.lastOrNull()
                                            if (result.isSuccess && focused == "search/more" && next != null) {
                                                move = FocusMove("search/title/${next.id}", (move?.revision ?: 0) + 1)
                                            }
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

    chosenFilter?.let { control ->
        val options = state.options[control.apiKey].orEmpty()
        val message = when {
            filterLoading -> "Loading filters…"
            filterFailed -> "Couldn’t load filters. Close and try again."
            options.isEmpty() && control.apiKey != null -> "No filter options available."
            else -> null
        }
        when (control) {
            SearchControl.GENRE -> MultiSelectFilters("Genres", options, state.genres.map(BrowseOption::value).toSet(),
                hint = "Match all selected genres", message = message, close = ::closeFilter) { values ->
                state.genres = options.filter { it.value in values }.toSet()
                closeFilter()
            }
            SearchControl.YEAR -> {
                val years = remember { (Year.now().value downTo FIRST_RELEASE_YEAR).map { BrowseOption(it.toString(), it.toString()) } }
                MultiSelectFilters("Release years", years, state.years.map(Int::toString).toSet(),
                    columns = 6, close = ::closeFilter) { values ->
                    state.years = values.mapNotNull(String::toIntOrNull).toSet()
                    closeFilter()
                }
            }
            SearchControl.SORT -> MediaDialog("Sort", null, SearchOrder.entries.map(SearchOrder::label),
                initial = state.order.ordinal, close = ::closeFilter) {
                state.order = SearchOrder.entries[it]
                closeFilter()
            }
            SearchControl.TYPE, SearchControl.RATING -> {
                val selection = if (control == SearchControl.TYPE) state.type else state.rating
                MediaDialog(control.label, message, listOf("Any") + options.map(BrowseOption::label),
                    initial = options.indexOf(selection) + 1, close = ::closeFilter) { choice ->
                    val value = options.getOrNull(choice - 1)
                    if (control == SearchControl.TYPE) state.type = value else state.rating = value
                    closeFilter()
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    id: String,
    move: FocusMove?,
    focused: (String) -> Unit,
    icon: Int?,
    click: () -> Unit,
) {
    val accent = if (active) focusColor else Color.Transparent
    Action(label, click, id, move, focused, Modifier.widthIn(max = 168.dp)
        .border(1.dp, accent, tileShape).semantics { selected = active }, icon = icon)
}

@Composable
private fun MultiSelectFilters(
    title: String,
    options: List<BrowseOption>,
    selection: Set<String>,
    columns: Int = 3,
    hint: String? = null,
    message: String? = null,
    close: () -> Unit,
    apply: (Set<String>) -> Unit,
) {
    var draft by remember { mutableStateOf(selection) }
    val start = options.indexOfFirst { it.value in selection }.coerceAtLeast(0)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = start)
    val target = FocusMove(options.getOrNull(start)?.let { "picker/${it.value}" } ?: "picker/clear", 0)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f).heightIn(max = 365.dp)
                .background(panel, mediaShape).border(1.dp, edge, mediaShape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                if (draft.isNotEmpty()) Text("${draft.size} selected", color = secondary, fontSize = 15.sp)
            }
            hint?.let { Text(it, color = secondary, fontSize = 15.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (options.isEmpty()) {
                    Text(message ?: "No options available.", Modifier.weight(1f).padding(5.dp),
                        color = secondary, fontSize = 17.sp)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(options, key = BrowseOption::value) { option ->
                            val checked = option.value in draft
                            FocusSurface("picker/${option.value}", target, {}, {
                                draft = if (checked) draft - option.value else draft + option.value
                            }, Modifier.fillMaxWidth().height(44.dp).semantics { selected = checked },
                                fill = if (checked) Color(0xFF565656) else null) {
                                Row(
                                    Modifier.fillMaxSize().padding(horizontal = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                ) {
                                    if (checked) Image(painterResource(R.drawable.media_check), null, Modifier.size(14.dp),
                                        colorFilter = ColorFilter.tint(LocalContentColor.current))
                                    Text(option.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Apply", { apply(draft) }, "picker/apply", target, {}, Modifier.fillMaxWidth(),
                        enabled = options.isNotEmpty())
                    Action("Clear", { draft = emptySet() }, "picker/clear", target, {}, Modifier.fillMaxWidth())
                }
            }
        }
    }
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
    var focusedAction by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 510.dp).fillMaxWidth(.72f).heightIn(max = 425.dp)
                .background(panel, mediaShape).border(1.dp, edge, mediaShape).padding(22.dp)
                .guardDialogSelection { focusedAction },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            message?.let { Text(it, color = secondary, fontSize = 17.sp) }
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                items(options.size) { i ->
                    Action(options[i], { select(i) }, "dialog/$i", FocusMove("dialog/$target", 0),
                        { focusedAction = it }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun MediaEpisode.pickerName(): String = name.trim().takeUnless {
    it.equals("Season $season - Episode $number", ignoreCase = true) || it.equals("Episode $number", ignoreCase = true)
}.orEmpty()

private fun EpisodeNumber.label() = if (season > 0 && episode > 0) "Season $season · Episode $episode"
    else if (episode > 0) "Special · Episode $episode" else "Special"

private fun WatchPosition.label(series: Boolean) = listOfNotNull(
    "Last played", episode.label().takeIf { series }, clockTime(timeMs / 1000),
).joinToString(" · ")

@Composable
internal fun EpisodePicker(
    episodes: List<MediaEpisode>,
    selected: MediaEpisode?,
    lastPlayed: WatchPosition?,
    onClose: () -> Unit,
    onSelect: (MediaEpisode) -> Unit,
) {
    if (episodes.isEmpty()) return
    val entries = remember(episodes) {
        episodes.groupBy(MediaEpisode::season).toSortedMap().flatMap { (season, items) ->
            listOf(season to null) + items.sortedBy(MediaEpisode::number).map { season to it }
        }
    }
    val initial = entries.indexOfFirst { (_, episode) ->
        episode != null && if (selected != null) episode == selected else episode.key == lastPlayed?.episode
    }.takeIf { it >= 0 } ?: 1
    var focused by remember(entries, initial) { mutableStateOf(entries[initial].second) }
    var focusOnOpen by remember(entries, initial) { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.widthIn(max = 1040.dp).fillMaxWidth(.9f).heightIn(max = 590.dp).fillMaxHeight(.86f)
                .background(panel, mediaShape).border(1.dp, edge, mediaShape).padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            val columns = (maxWidth / 111.dp).toInt().coerceIn(3, 8)
            val header = entries.indexOfLast { it.second == null && it.first == entries[initial].first }
            val grid = rememberLazyGridState(if (initial - header <= columns * 3) header else initial)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Episodes", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                lastPlayed?.let { Text(it.label(true), color = foreground, fontSize = 16.sp) }
                Text(
                    focused?.let {
                        val number = if (it.number > 0) "Episode ${it.number}" else "Special"
                        val season = if (it.season > 0) "Season ${it.season}" else "Specials"
                        listOf(season, number, it.pickerName()).filter(String::isNotBlank).joinToString(" · ")
                    }.orEmpty(),
                    color = secondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                ) {
                    items(entries.size, key = { it }, span = { index ->
                        GridItemSpan(if (entries[index].second == null) maxLineSpan else 1)
                    }) { index ->
                        val (season, episode) = entries[index]
                        if (episode == null) {
                            Text(
                                if (season == 0) "Specials" else "Season $season",
                                modifier = Modifier.padding(top = 7.dp, bottom = 1.dp),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            val shape = RoundedCornerShape(12.dp)
                            val watched = lastPlayed?.takeIf { it.episode == episode.key }
                            val current = if (episode == selected || watched != null) {
                                Modifier.border(1.dp, focusColor, shape)
                            } else Modifier
                            val move = if (focusOnOpen) FocusMove("episode/$initial", 0) else null
                            val name = watched?.let { "Resume ${clockTime(it.timeMs / 1000)}" } ?: episode.pickerName()
                            FocusSurface("episode/$index", move, {
                                focused = episode
                                if (index == initial) focusOnOpen = false
                            },
                                { onSelect(episode) }, current.height(58.dp)) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 9.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text(
                                        if (episode.number > 0) "Episode ${episode.number}" else "Special",
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    )
                                    if (name.isNotBlank()) Text(
                                        name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TitleRatings(ratings: List<MediaRating>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (rating in ratings) {
            val icon = when (rating.source) {
                RatingSource.IMDB -> R.drawable.score_imdb
                RatingSource.NAMA -> R.drawable.score_nama
                RatingSource.ROTTEN_TOMATOES -> R.drawable.score_rotten_tomatoes
                RatingSource.METACRITIC -> R.drawable.score_metacritic
                RatingSource.MY_DRAMA_LIST -> R.drawable.score_mydramalist
                RatingSource.MY_ANIME_LIST -> R.drawable.score_myanimelist
            }
            val votes = rating.votes.takeIf { rating.source == RatingSource.IMDB && it > 0 }
            val compactVotes = votes?.let {
                if (it < 1000) it.toString() else String.format(Locale.ROOT, "%.1fK", it / 1000.0)
            }
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = "${rating.source.label} ${rating.score}" +
                        (votes?.let { ", ${String.format(Locale.getDefault(), "%,d", it)} votes" } ?: "")
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(icon), null, Modifier.size(21.dp).clip(CircleShape))
                Text(rating.score, color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                compactVotes?.let { Text("($it)", color = secondary, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaTitleScreen(
    activity: MainActivity,
    original: MediaTitle,
    onConnect: () -> Unit,
    heroEpisode: EpisodeNumber?,
    onClose: () -> Unit,
) {
    val media = activity.media
    val scope = rememberCoroutineScope()
    var title by remember(original.id) { mutableStateOf(original) }
    var catalog by remember(original.id, media.accountRevision) { mutableStateOf<PlaybackCatalog?>(null) }
    var playing by remember(original.id, media.accountRevision) { mutableStateOf<MediaEpisode?>(null) }
    var picking by remember(original.id, media.accountRevision) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var actionFocus by remember { mutableStateOf(FocusMove("title/play", 0)) }
    var focusedAction by remember { mutableStateOf("title/play") }
    val detailScroll = rememberScrollState()
    val scrollStep = with(LocalDensity.current) { 80.dp.roundToPx() }

    suspend fun startPlayback() {
        busy = true
        notice = null
        val data = tryNama { media.playback(title) }.getOrNull()
        if (data == null || data.episodes.isEmpty()) {
            notice = if (media.account?.days?.let { it <= 0 } == true) GOLD_EXPIRED
                else "No playable source is available right now."
            actionFocus = FocusMove("title/play", actionFocus.revision + 1)
        } else {
            catalog = data
            val requestedEpisode = heroEpisode?.let { key -> data.episodes.firstOrNull { it.key == key } }
            if (title.series && requestedEpisode == null) picking = true
            else playing = requestedEpisode ?: data.episodes.first()
        }
        busy = false
    }

    DisposableEffect(original.id, media.accountRevision) { onDispose { media.clearPlaybackReads() } }
    LaunchedEffect(original.id, media.accountRevision) {
        tryNama { media.detail(original) }.getOrNull()?.let { title = it }
        tryNama { media.refreshTitlePosition(title) }
    }
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
    val lastPlayed = media.lastPlayed(title.id)
    BoxWithConstraints(Modifier.fillMaxSize().background(ink).onPreviewKeyEvent {
        val event = it.nativeKeyEvent
        if (focusedAction != "title/play" || event.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> scrollStep
            KeyEvent.KEYCODE_DPAD_UP -> -scrollStep
            else -> return@onPreviewKeyEvent false
        }
        val target = (detailScroll.value + delta).coerceIn(0, detailScroll.maxValue)
        if (target == detailScroll.value) return@onPreviewKeyEvent false
        scope.launch { detailScroll.animateScrollTo(target) }
        true
    }) {
        val margin = (maxWidth * .045f).coerceIn(24.dp, 64.dp)
        title.cover?.let { CatalogImage(media, it, Modifier.fillMaxSize(), 1600) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Color(0xF5000000), .21f to Color(0xEA000000), .44f to Color(0xCC000000),
            .64f to Color(0x20000000), 1f to Color(0x17000000),
        )))
        if (title.cover == null && title.poster != null) {
            CatalogImage(
                media, title.poster, Modifier.align(Alignment.CenterEnd).padding(end = margin + 78.dp)
                    .size(142.dp, 213.dp).clip(mediaShape), 480,
            )
        }
        FocusSurface(
            "title/play", actionFocus, { focusedAction = it },
            {
                if (!media.configured || (!media.connected && !title.freeStream && !title.freeDownload)) onConnect()
                else scope.launch { startPlayback() }
            },
            Modifier.align(Alignment.Center).size(76.dp).semantics { contentDescription = "Play" },
            enabled = !busy, glassShape = CircleShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(57.dp).border(2.dp, foreground, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.media_play), null, Modifier.size(38.dp),
                        colorFilter = ColorFilter.tint(Color(0xFFFA605B)))
                }
            }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = margin)
                .background(Color(0xAA1C1C1C), mediaShape).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val lists = listOf(
                Triple("favorite", "Like" to "Remove like", R.drawable.media_like to R.drawable.media_liked),
                Triple("watchlist", "Add to Watchlist" to "Remove from Watchlist",
                    R.drawable.media_watchlist to R.drawable.media_watchlisted),
                Triple("notify", "Notify me" to "Turn off notifications", R.drawable.media_notify to R.drawable.media_notified),
            )
            for ((group, labels, icons) in lists) {
                val saved = media.has(group, title.id)
                MediaIconButton(
                    if (saved) labels.second else labels.first, if (saved) icons.second else icons.first,
                    "title/$group", actionFocus, { focusedAction = it }, enabled = !busy,
                    saved = saved.takeIf { media.knows(group) },
                ) {
                    if (!media.connected || !media.configured) onConnect() else scope.launch {
                        if (!media.knows(group)) {
                            notice = "Your lists are still unavailable. Try again in a moment."
                            media.refresh(home = false, force = true)
                            return@launch
                        }
                        busy = true
                        notice = null
                        if (!media.toggle(group, title.id)) notice = "Couldn’t confirm the change. Try again."
                        busy = false
                        actionFocus = FocusMove("title/$group", actionFocus.revision + 1)
                    }
                }
            }
        }
        val width = (maxWidth * .39f).coerceAtMost(480.dp)
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = margin)
                .width(width).heightIn(max = maxHeight - 56.dp).verticalScroll(detailScroll),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title.name, Modifier.semantics { heading() },
                    fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                    style = TextStyle(lineBreak = LineBreak.Heading, textDirection = TextDirection.Content),
                )
                if (title.localized.isNotBlank()) {
                    Text(title.localized, fontSize = 15.sp, lineHeight = 20.sp, color = secondary,
                        style = TextStyle(textDirection = TextDirection.Content))
                }
                val year = title.year.toString()
                val facts = buildList {
                    if (title.year > 0 && title.name != year && !title.name.endsWith(" $year")) add(year)
                    if (title.runtime > 0) add("${title.runtime} min")
                    if (title.series && title.seasons > 0) {
                        add(if (title.seasons == 1) "1 season" else "${title.seasons} seasons")
                    }
                    addAll(title.countries)
                }
                if (title.ageRating.isNotBlank() || facts.isNotEmpty()) FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title.ageRating.isNotBlank()) Text(
                        title.ageRating,
                        Modifier.border(1.dp, edge, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        color = foreground, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    for (fact in facts) Text(fact, color = secondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (title.genres.isNotEmpty()) Text(
                    title.genres.joinToString(" · "), color = secondary, fontSize = 13.sp, lineHeight = 18.sp,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
            }
            if (title.ratings.isNotEmpty()) TitleRatings(title.ratings)
            if (lastPlayed != null || (title.series && title.nextEpisode.isNotBlank())) Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                lastPlayed?.let { Text(it.label(title.series), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium) }
                if (title.series && title.nextEpisode.isNotBlank()) {
                    Text(title.nextEpisode, color = secondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (title.description.isNotBlank()) Text(
                Html.fromHtml(title.description, Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                fontSize = 15.sp, lineHeight = 22.sp,
                style = TextStyle(lineBreak = LineBreak.Paragraph, textDirection = TextDirection.Content),
            )
            if (title.cast.isNotEmpty()) Row(
                Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (person in title.cast) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CatalogImage(
                            media, person.photo, Modifier.size(51.dp, 65.dp).clip(RoundedCornerShape(8.dp)), 260,
                        ) {
                            Image(painterResource(R.drawable.media_person), null, Modifier.size(27.dp),
                                colorFilter = ColorFilter.tint(secondary.copy(alpha = .52f)))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(person.name, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                                style = TextStyle(textDirection = TextDirection.Content))
                            if (person.role.isNotBlank()) Text(person.role, color = secondary, fontSize = 12.sp,
                                lineHeight = 16.sp, style = TextStyle(textDirection = TextDirection.Content))
                        }
                    }
                }
            }
            if (busy) Text("Contacting 30nama…", color = secondary, fontSize = 13.sp)
            notice?.let { Text(it, color = Color(0xFFFFDCA6), fontSize = 13.sp) }
        }
    }

    if (picking && video != null) {
        EpisodePicker(video.episodes, selected = null, lastPlayed = lastPlayed,
            onClose = { picking = false; actionFocus = FocusMove("title/play", actionFocus.revision + 1) }) {
            picking = false
            playing = it
        }
    }
}
