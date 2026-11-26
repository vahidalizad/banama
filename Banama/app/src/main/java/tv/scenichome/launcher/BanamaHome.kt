package tv.scenichome.launcher

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
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
internal data class FocusMove(val id: String, val revision: Int)
internal fun heroKey(id: Int) = "home/hero/$id"

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
    var settings by remember { mutableStateOf(false) }
    var heroMenu by remember(accountRevision) { mutableStateOf<UpcomingEpisode?>(null) }
    var lastFocus by rememberSaveable { mutableStateOf("home/search") }
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }

    val upcoming = media.heroEpisodes
    val latest = upcoming.firstOrNull()
    var heroTitleId by remember(latest?.title?.id, accountRevision) { mutableIntStateOf(0) }
    val hero = upcoming.firstOrNull { it.title.id == heroTitleId } ?: latest
    val shelves = buildList {
        MediaLibrary.entries.filter { it == MediaLibrary.WATCHLIST || media.connected }.forEach { library ->
            add(Triple(library.key, library.label, media.shelves[library]?.titles.orEmpty().take(8)))
        }
        if (media.suggestions.isNotEmpty()) add(Triple("suggested", "Suggested", media.suggestions))
    }
    val targets = listOf("home/search", "home/account") + upcoming.map { heroKey(it.title.id) } +
        shelves.flatMap { (key, _, titles) ->
            val library = MediaLibrary.entries.firstOrNull { it.key == key }
            titles.map { "home/$key/${it.id}" } + listOfNotNull(
                "home/$key/all".takeIf { library != null && library in media.shelves },
                "home/$key/status".takeIf { library != null && (!media.connected || media.libraryRetry(library)) },
            )
        }
    val dialogOpen = settings || heroMenu != null
    val focusLost = lastFocus !in targets
    LaunchedEffect(activity.foreground, dialogOpen, focusLost) {
        if (!activity.foreground || dialogOpen) return@LaunchedEffect
        val target = lastFocus.takeIf { !focusLost } ?: "home/search"
        val group = target.split('/')[1]
        val item = shelves.indexOfFirst { it.first == group }
        if (item >= 0) {
            val index = item + if (hero == null) 0 else 1
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        } else if (group == "hero" && hero != null) {
            list.scrollToItem(0)
        }
        move = FocusMove(target, ++sequence)
    }
    val focused: (String) -> Unit = { if (!dialogOpen) lastFocus = it }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Banama", Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Action("Search", { onSearch(null) }, "home/search", move, focused, icon = R.drawable.media_search)
            Action("30nama", {
                if (media.connected) settings = true else onConnect()
            }, "home/account", move, focused)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (hero != null) item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Continue Watching", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    EpisodeHero(
                        media, upcoming, hero, move,
                        focused = { episode ->
                            heroTitleId = episode.title.id
                            focused(heroKey(heroTitleId))
                        },
                        open = onHero, options = { heroMenu = it },
                        showOptionsHint = lastFocus == heroKey(hero.title.id),
                    )
                }
            }
            items(shelves.size, key = { shelves[it].first }) { index ->
                val (key, label, titles) = shelves[index]
                val library = MediaLibrary.entries.firstOrNull { it.key == key }
                MediaShelf(
                    media, key, label, titles, move, focused, onTitle, onConnect,
                    onRetry = { scope.launch { media.refresh(home = false, library = library, force = true) } },
                    library = library, onSeeAll = { onSearch(library) },
                )
            }
        }
    }

    if (settings) {
        MediaDialog("30nama", null, listOf("Pair again", "Disconnect 30nama"), close = { settings = false }) {
            settings = false
            if (it == 0) onConnect() else scope.launch { media.disconnect() }
        }
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
internal fun Action(
    title: String,
    onClick: () -> Unit,
    id: String,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    FocusSurface(id, move, onFocused, onClick, modifier.height(48.dp), enabled = enabled) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    content: @Composable () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val shape = glassShape ?: tileShape
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
            containerColor = if (glassShape != null) Color(0xAB161616) else fill ?: Color(0xFF303030),
            contentColor = foreground,
            focusedContainerColor = if (glassShape != null) Color(0xAB161616) else if (contentShowsFocus) Color.Transparent else focusColor,
            focusedContentColor = if (transparentFocus) foreground else ink,
            pressedContainerColor = when {
                contentShowsFocus -> Color.Transparent
                glassShape != null -> Color(0xC93A3A3A)
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
