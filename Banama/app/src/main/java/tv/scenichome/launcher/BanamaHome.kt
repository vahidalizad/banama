package tv.scenichome.launcher

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val ink = Color(0xFF101010)
internal val panel = Color(0xFF1C1C1C)
internal val focusColor = Color(0xFFF1F1F1)
internal val foreground = Color(0xFFF5F5F5)
internal val secondary = Color(0xFFCBCBCB)
internal val edge = Color(0xFF585858)
internal val tileShape = RoundedCornerShape(12.dp)
private val headerGlass = Color(0x4814101B)
internal data class FocusMove(val id: String, val revision: Int)
internal fun heroKey(id: Int) = "home/hero/$id"

internal fun initialHomeFocus(resumeId: Int?, suggestedId: Int?, waitingForResume: Boolean, waitingForSuggestions: Boolean): String? =
    when {
        resumeId != null -> heroKey(resumeId)
        waitingForResume || waitingForSuggestions -> null
        suggestedId != null -> "home/suggested/$suggestedId"
        else -> "home/search"
    }

@Composable
internal fun BanamaHome(activity: MainActivity) {
    val media = activity.media
    val stateHolder = rememberSaveableStateHolder()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var title by remember { mutableStateOf<MediaTitle?>(null) }
    var heroEpisode by remember { mutableStateOf<EpisodeNumber?>(null) }
    var searchLibrary by rememberSaveable { mutableStateOf<MediaLibrary?>(null) }
    val catalogSearch = remember { MediaSearchState() }
    val savedSearch = remember(media.accountRevision) {
        MediaLibrary.entries.associateWith { MediaSearchState(it) }
    }
    val search = savedSearch[searchLibrary] ?: catalogSearch
    val heading = title
    val homeVisible = !pairing && heading == null && !searchOpen
    val visibleLibrary = searchLibrary.takeIf { searchOpen && heading == null && !pairing }

    LaunchedEffect(activity.foreground, media.accountRevision, homeVisible, visibleLibrary) {
        if (activity.foreground) while (true) {
            delay(media.refresh(homeVisible, visibleLibrary))
        }
    }
    LaunchedEffect(activity.foreground, homeVisible, media.accountRevision, media.catalogRevision) {
        if (activity.foreground && homeVisible) media.refreshUpcoming()
    }
    LaunchedEffect(activity.notice) {
        if (activity.notice != null) {
            delay(4_000)
            activity.clearNotice()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = focusColor, onPrimary = ink, surface = panel, onSurface = foreground,
            background = ink, onBackground = foreground,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Box(Modifier.fillMaxSize().background(ink)) {
                when {
                    pairing -> PairNamaScreen(activity, onClose = { pairing = false }, onConnected = {
                        pairing = false
                    })
                    heading != null -> stateHolder.SaveableStateProvider("title/${heading.id}") {
                        MediaTitleScreen(activity, heading, { pairing = true }, heroEpisode) {
                            title = null
                            heroEpisode = null
                        }
                    }
                    searchOpen -> stateHolder.SaveableStateProvider("search/${searchLibrary?.key ?: "catalog"}") {
                        MediaSearchScreen(activity, search, { title = it }) { searchOpen = false }
                    }
                    else -> stateHolder.SaveableStateProvider("home") {
                        HomeScreen(
                            activity, { library ->
                                searchLibrary = library
                                searchOpen = true
                            }, { pairing = true },
                            onTitle = { title = it },
                            onHero = { heroEpisode = it.episode; title = it.title },
                        )
                    }
                }
                activity.notice?.let { message ->
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                            .background(panel, RoundedCornerShape(16.dp))
                            .border(1.dp, edge, RoundedCornerShape(16.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) { Text(message, color = foreground, fontSize = 15.sp) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeScreen(
    activity: MainActivity,
    onSearch: (MediaLibrary?) -> Unit,
    onConnect: () -> Unit,
    onTitle: (MediaTitle) -> Unit,
    onHero: (UpcomingEpisode) -> Unit,
) {
    val media = activity.media
    val accountRevision = media.accountRevision
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var profileOpen by remember { mutableStateOf(false) }
    var heroMenu by remember(accountRevision) { mutableStateOf<UpcomingEpisode?>(null) }
    var lastFocus by rememberSaveable { mutableStateOf("") }
    var currentFocus by remember { mutableStateOf("home/search") }
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }
    var revealTarget by remember { mutableStateOf<String?>(null) }
    var previewAbove by rememberSaveable { mutableStateOf(false) }
    val rowScroll = LocalBringIntoViewSpec.current
    val headerInset = with(LocalDensity.current) { 78.dp.toPx() }
    val shelfPreview = with(LocalDensity.current) { 132.dp.toPx() }
    val homeScroll = remember(headerInset, shelfPreview) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val room = containerSize - headerInset - size
                if (room < 0f) return super.calculateScrollDistance(offset, size, containerSize)
                // The hero keeps its full height when the episode row has focus.
                val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                val inHero = first != null && first.index == 0 && offset + size / 2 < first.offset + first.size
                val preview = if (inHero) 0f else minOf(shelfPreview, room)
                val top = headerInset + if (previewAbove) preview else 0f
                if (offset < top) return offset - top
                val bottomInset = if (previewAbove) 0f else preview
                return (offset + size + bottomInset - containerSize).coerceAtLeast(0f)
            }
        }
    }

    val upcoming = media.heroEpisodes
    val latest = upcoming.firstOrNull()
    var heroTitleId by remember(latest?.title?.id, accountRevision) { mutableIntStateOf(0) }
    val hero = upcoming.firstOrNull { it.title.id == heroTitleId } ?: latest
    val watchedSeries = hero?.title?.takeIf { media.connected && it.series }
        ?: upcoming.firstOrNull { media.connected && it.title.series }?.title
    val shelves = buildList {
        MediaLibrary.entries.filter { it == MediaLibrary.WATCHLIST || media.connected }.forEach { library ->
            add(Triple(library.key, library.label, media.shelves[library]?.titles.orEmpty().take(8)))
        }
        if (media.suggestions.isNotEmpty()) add(Triple("suggested", "Suggested", media.suggestions))
    }
    val headerTargets = listOf("home/search", "home/account")
    val focusRows = (listOf(headerTargets, upcoming.map { heroKey(it.title.id) }) +
        shelves.flatMap { (key, _, titles) ->
            val library = MediaLibrary.entries.firstOrNull { it.key == key }
            listOf(
                listOfNotNull("home/$key/status".takeIf {
                    library == MediaLibrary.WATCHLIST && !media.connected ||
                        library != null && media.libraryRetry(library)
                }),
                titles.map { "home/$key/${it.id}" } +
                    listOfNotNull("home/$key/all".takeIf { library != null && library in media.shelves }),
            )
        }).filter { it.isNotEmpty() }
    val targets = focusRows.flatten()
    val dialogOpen = profileOpen || heroMenu != null
    val focusLost = lastFocus !in targets
    val focusAtTop = lastFocus in headerTargets || lastFocus.startsWith("home/hero/")
    val initialTarget = initialHomeFocus(latest?.title?.id, media.suggestions.firstOrNull()?.id,
        media.configured && media.connected && !media.historyLoaded, media.configured && !media.homeLoaded)
    LaunchedEffect(revealTarget, focusAtTop) {
        val target = revealTarget?.let { if (it in targets) it else "home/search" }
        val index = shelves.indexOfFirst { (key, _, _) -> target?.startsWith("home/$key/") == true } + 1
        if (index > 0) {
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                val inset = minOf((headerInset + shelfPreview).toInt(), list.layoutInfo.viewportSize.height / 2)
                list.animateScrollToItem(index, -inset)
            }
        } else if ((target != null || focusAtTop) && list.canScrollBackward) {
            list.animateScrollToItem(0)
        }
        if (target != null) {
            move = FocusMove(target, ++sequence)
            revealTarget = null
        }
    }
    val recoveryTarget = initialTarget.takeIf { focusLost }
    LaunchedEffect(activity.foreground, dialogOpen, focusLost, recoveryTarget) {
        if (!activity.foreground || dialogOpen) return@LaunchedEffect
        val target = lastFocus.takeIf { !focusLost } ?: recoveryTarget ?: return@LaunchedEffect
        val group = target.split('/')[1]
        val index = shelves.indexOfFirst { it.first == group } + 1
        if (index > 0) {
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        }
        move = FocusMove(target, ++sequence)
    }
    val focused: (String) -> Unit = { id ->
        if (!dialogOpen) {
            currentFocus = id
            if (move != null || lastFocus.isNotEmpty()) lastFocus = id
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {
        val key = it.nativeKeyEvent
        if (dialogOpen) return@onPreviewKeyEvent false
        if (revealTarget != null && key.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
            return@onPreviewKeyEvent true
        }
        val firstPress = key.action == KeyEvent.ACTION_DOWN
        if (firstPress && lastFocus.isEmpty() && key.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
            lastFocus = currentFocus
        }
        if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && firstPress) previewAbove = false
        val target = when {
            key.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                val index = focusRows.indexOfFirst { lastFocus in it }
                val previous = focusRows.getOrNull(index - 1)
                when {
                    previous == null -> null
                    previous == headerTargets -> "home/search"
                    hero != null && heroKey(hero.title.id) in previous -> heroKey(hero.title.id)
                    else -> previous[focusRows[index].indexOf(lastFocus).coerceAtMost(previous.lastIndex)]
                }
            }
            key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && lastFocus in headerTargets ->
                hero?.let { episode -> heroKey(episode.title.id) }
            else -> null
        }
        if (target != null && firstPress) {
            previewAbove = key.keyCode == KeyEvent.KEYCODE_DPAD_UP
            revealTarget = target
        }
        target != null
    }) {
        val stageHeight = if (hero == null && media.suggestions.isEmpty()) 90.dp
            else (maxHeight * .72f).coerceIn(254.dp, 510.dp)
        CompositionLocalProvider(LocalBringIntoViewSpec provides homeScroll) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item(key = "hero") {
                    Box(Modifier.fillMaxWidth().height(stageHeight).clipToBounds()) {
                        key(accountRevision) {
                            HomeArtwork(media, watchedSeries, showSuggestedTitle = hero == null)
                        }
                        if (hero != null) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
                                EpisodeHero(
                                    media, upcoming, hero, move,
                                    focused = { episode ->
                                        heroTitleId = episode.title.id
                                        focused(heroKey(heroTitleId))
                                    },
                                    open = onHero, options = { heroMenu = it },
                                    showOptionsHint = lastFocus == heroKey(hero.title.id),
                                    modifier = Modifier.align(Alignment.BottomStart)
                                        .padding(start = 40.dp, end = 40.dp, bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
                items(shelves.size, key = { shelves[it].first }) { index ->
                    val (key, label, titles) = shelves[index]
                    val library = MediaLibrary.entries.firstOrNull { it.key == key }
                    Box(Modifier.padding(horizontal = 40.dp)) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
                            MediaShelf(
                                media, key, label, titles, move, focused, onTitle, onConnect,
                                onRetry = { scope.launch { media.refresh(home = false, library = library, force = true) } },
                                library = library, onSeeAll = { onSearch(library) },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(102.dp).background(Brush.verticalGradient(
            listOf(Color(0xCB101010), Color(0x53101010), Color.Transparent),
        )))
        Row(
            Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Image(
                    painterResource(R.drawable.banama_header), stringResource(R.string.app_name),
                    Modifier.height(36.dp),
                )
            }
            Action(
                "Search", { onSearch(null) }, "home/search", move, focused, Modifier.width(136.dp),
                icon = R.drawable.media_search, glassTint = headerGlass,
            )
            FocusSurface(
                "home/account", move, focused, { profileOpen = true },
                Modifier.size(48.dp).semantics { contentDescription = "Profile" },
                glassShape = CircleShape, glassTint = headerGlass,
            ) { ProfileBadge(media.account, Modifier.fillMaxSize()) }
        }
    }

    if (profileOpen) {
        ProfileDialog(
            media.account, media.connected,
            close = { profileOpen = false },
            connect = {
                profileOpen = false
                onConnect()
            },
            disconnect = {
                profileOpen = false
                scope.launch { media.disconnect() }
            },
        )
    }
    heroMenu?.let { episode ->
        MediaDialog(
            title = episode.title.name,
            message = "Hide this title from Continue Watching. Your progress is saved.",
            options = listOf("I don’t watch this anymore", "Cancel"),
            initial = 1,
            close = { heroMenu = null },
        ) { choice ->
            heroMenu = null
            if (choice == 0) scope.launch { media.hideHeroTitle(episode.title.id, accountRevision) }
        }
    }
}

@Composable
private fun HomeArtwork(media: MediaStore, watched: MediaTitle?, showSuggestedTitle: Boolean) {
    val suggestions = media.suggestions.take(4)
    val artwork by produceState<Pair<MediaTitle, Bitmap>?>(null, watched, suggestions) {
        value = (listOfNotNull(watched) + suggestions).firstNotNullOfOrNull { title ->
            listOfNotNull(title.cover, title.poster).distinct().firstNotNullOfOrNull { url ->
                media.artwork.image(url, 1920)?.let { title to it }
            }
        }
    }
    val (title, bitmap) = artwork ?: return
    val portrait = bitmap.height > bitmap.width
    Box(Modifier.fillMaxSize()) {
        val bounds = if (portrait) {
            Modifier.align(Alignment.CenterEnd).padding(top = 68.dp, bottom = 16.dp, end = 54.dp)
                .fillMaxHeight().aspectRatio(bitmap.width.toFloat() / bitmap.height)
        } else Modifier.fillMaxSize()
        Image(remember(bitmap) { bitmap.asImageBitmap() }, null, bounds, contentScale = ContentScale.Crop)
        if (!portrait) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color(0xA7101010), .44f to Color(0x43101010), 1f to Color.Transparent,
            )))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                .53f to Color.Transparent, 1f to ink,
            )))
        }
        if (showSuggestedTitle) {
            Text(
                title.name,
                Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 20.dp).widthIn(max = 460.dp),
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun Action(
    title: String,
    onClick: () -> Unit,
    id: String,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    glassTint: Color? = null,
) {
    FocusSurface(id, move, onFocused, onClick, modifier.height(48.dp), enabled = enabled,
        glassShape = tileShape.takeIf { glassTint != null }, glassTint = glassTint) {
        Row(
            Modifier.fillMaxHeight()
                .then(if (glassTint == null) Modifier else Modifier.fillMaxWidth())
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                8.dp, if (glassTint == null) Alignment.Start else Alignment.CenterHorizontally,
            ),
        ) {
            icon?.let {
                Image(painterResource(it), null, Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(LocalContentColor.current))
            }
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun FocusSurface(
    id: String,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    glassShape: Shape? = null,
    fill: Color? = null,
    glassTint: Color? = null,
    content: @Composable () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val shape = glassShape ?: tileShape
    val glassColor = glassTint ?: Color(0xAB161616)
    val contentShowsFocus = fill == Color.Transparent
    val transparentFocus = glassShape != null || contentShowsFocus
    LaunchedEffect(move, enabled) {
        if (move?.id == id && enabled) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) onFocused(id)
        },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (glassShape != null) glassColor else fill ?: Color(0xFF303030),
            contentColor = foreground,
            focusedContainerColor = if (glassShape != null) glassColor else if (contentShowsFocus) Color.Transparent else focusColor,
            focusedContentColor = if (transparentFocus) foreground else ink,
            pressedContainerColor = when {
                contentShowsFocus -> Color.Transparent
                glassShape != null -> glassTint?.copy(alpha = .43f) ?: Color(0xC93A3A3A)
                else -> focusColor
            },
            pressedContentColor = if (transparentFocus) foreground else ink,
            disabledContainerColor = panel, disabledContentColor = secondary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (glassShape != null) {
                Border(BorderStroke(.75.dp, Color(0x38FFFFFF)), shape = shape)
            } else Border.None,
            focusedBorder = if (contentShowsFocus) Border.None else Border(
                BorderStroke(if (glassShape != null) 1.5.dp else 2.dp, Color.White), shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = when {
            contentShowsFocus -> 1f
            glassShape != null -> 1.045f
            else -> 1.025f
        }),
    ) { content() }
}

@Composable
internal fun Modifier.guardDialogSelection(focusedAction: () -> String?): Modifier {
    val press = remember { MenuSelectPress() }
    return onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        when (key.keyCode) {
            // The dialog may receive the repeat or release of the hold that opened it.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                press.consume(key.keyCode, key.action == KeyEvent.ACTION_DOWN, key.repeatCount, focusedAction()) || key.isCanceled
            else -> false
        }
    }
}
