package tv.scenichome.launcher

import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val ink = Color(0xFF101010)
internal val panel = Color(0xFF1C1C1C)
internal val focusColor = Color(0xFFF1F1F1)
internal val foreground = Color(0xFFF5F5F5)
internal val secondary = Color(0xFFCBCBCB)
internal val edge = Color(0xFF585858)
private val dockShape = RoundedCornerShape(24.dp)
internal val tileShape = RoundedCornerShape(12.dp)
private val glassTile = RoundedCornerShape(16.dp)
private const val SHELF_HEADING = "apps/heading"
// Keep a visible app at its current position instead of pivoting it toward the top of the TV.
private val shelfFocusScroll = object : BringIntoViewSpec {}

internal data class FocusMove(val id: String, val revision: Int)
private data class Setting(
    val key: String,
    val title: String,
    val enabled: Boolean = true,
    val keepOpen: Boolean = false,
    val run: () -> Unit,
)

private fun dockKey(app: TvApp) = "home/app/${app.pkg}"
private fun shelfKey(app: TvApp) = "apps/app/${app.pkg}"
internal fun heroKey(id: Int) = "home/hero/$id"

@Composable
internal fun ScenicHome(activity: MainActivity) {
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
    val homeGrid = rememberLazyGridState()
    val appGrid = rememberLazyGridState()
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    val grid = if (appsOpen) appGrid else homeGrid
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    var connectionMenu by remember { mutableStateOf(false) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var lastDockFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var heroTitleId by rememberSaveable { mutableIntStateOf(0) }
    var suggestedCover by rememberSaveable { mutableStateOf<String?>(null) }
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }
    val scope = rememberCoroutineScope()

    val apps = activity.apps
    val dock = remember(apps, activity.pinned, activity.recent) {
        val byId = apps.associateBy { it.pkg }
        dockApps(apps.map { it.pkg }, activity.pinned, activity.recent).mapNotNull(byId::get)
    }
    val media = activity.media
    val scenes = buildList {
        for (library in MediaLibrary.entries) {
            if (library == MediaLibrary.WATCHLIST || media.connected) {
                add(Triple(library.key, library.label, media.shelves[library]?.titles.orEmpty().take(8)))
            }
        }
        if (media.suggestions.isNotEmpty()) add(Triple("suggested", "Suggested", media.suggestions))
    }
    val upcoming = if (appsOpen) emptyList() else
        (media.upcoming + media.resumeEpisodes).distinctBy { it.title.id }
    val hero = upcoming.firstOrNull { it.title.id == heroTitleId } ?: upcoming.firstOrNull()
    val stageLoading = !appsOpen && hero == null && media.configured &&
        (!media.homeLoaded || (media.connected && !media.upcomingLoaded))
    val useSuggested = !appsOpen && !stageLoading && hero == null
    val covers = when {
        appsOpen || stageLoading -> emptyList()
        hero?.title?.cover != null -> listOfNotNull(hero.title.cover)
        else -> (listOfNotNull(suggestedCover) + media.suggestions.mapNotNull(MediaTitle::cover)).distinct().take(4)
    }
    val backdrop by produceState<Bitmap?>(null, covers, useSuggested, media.accountRevision) {
        value = covers.firstNotNullOfOrNull { url ->
            media.artwork.image(url, 1920)?.takeIf { it.width >= it.height }?.also {
                if (useSuggested) suggestedCover = url
            }
        }
    }
    val hasStage = !appsOpen && (stageLoading || hero != null || covers.isNotEmpty())
    val dialogOpen = appMenu != null || connectionMenu
    val dockTargets = dock.map(::dockKey) + "home/apps"
    val homeTargets = dockTargets + listOf("home/search", "home/play", "home/settings") +
        upcoming.map { heroKey(it.title.id) } +
        scenes.flatMap { (key, _, titles) ->
            val library = MediaLibrary.entries.firstOrNull { it.key == key }
            titles.map { "home/$key/${it.id}" } +
                listOfNotNull(
                    "home/$key/all".takeIf { library != null && library in media.shelves },
                    "home/$key/status".takeIf { library != null && (!media.connected || media.libraryRetry(library)) },
                )
        }
    val targets = if (appsOpen) apps.map(::shelfKey) + homeTargets else homeTargets
    val focusLost = lastFocus !in targets && lastFocus?.substringBeforeLast('/') in
        listOf("home/watch", "home/notify", "home/liked", "home/suggested", "home/hero")
    val dockIndex = if (hasStage) 1 else 0

    LaunchedEffect(hasStage) {
        if (hasStage && lastFocus in dockTargets) homeGrid.scrollToItem(0)
    }
    LaunchedEffect(activity.foreground, activity.appsLoaded, appsOpen, dialogOpen, focusLost) {
        if (!activity.foreground || !activity.appsLoaded || dialogOpen) return@LaunchedEffect
        val target = focusAfterRefresh(lastFocus, targets) ?: return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.totalItemsCount }.first { it > 0 }
        val group = target.split('/').getOrNull(1)
        val index = when {
            appsOpen && target.startsWith("apps/") -> apps.indexOfFirst { shelfKey(it) == target } + 2
            group == "hero" -> 0
            !appsOpen && scenes.any { it.first == group } ->
                dockIndex + 1 + scenes.indexOfFirst { it.first == group }
            else -> dockIndex
        }
        val needsScroll = if (group == "hero") grid.canScrollBackward
            else index >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }
        if (needsScroll) grid.scrollToItem(index)
        move = FocusMove(target, ++sequence)
    }

    val onFocused: (String) -> Unit = { id ->
        if (!dialogOpen && activity.appsLoaded) {
            lastFocus = id
            if (id.startsWith("home/app")) lastDockFocus = id
            if (id.startsWith("home/hero/") && homeGrid.canScrollBackward) homeGrid.requestScrollToItem(0)
        }
    }
    BackHandler(appsOpen && !dialogOpen) {
        lastFocus = "home/apps"
        appsOpen = false
    }
    BackHandler(!appsOpen && !dialogOpen && grid.canScrollBackward) {
        scope.launch {
            grid.scrollToItem(0)
            move = FocusMove(dock.firstOrNull()?.let(::dockKey) ?: "home/apps", ++sequence)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ink).onPreviewKeyEvent {
        val key = it.nativeKeyEvent
        val heroTarget = hero?.let { episode -> heroKey(episode.title.id) }
        val target = when {
            dialogOpen -> null
            key.keyCode == KeyEvent.KEYCODE_DPAD_UP && lastFocus in dockTargets -> heroTarget ?: "home/search"
            key.keyCode == KeyEvent.KEYCODE_DPAD_UP && lastFocus?.startsWith("home/hero/") == true -> "home/search"
            key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && lastFocus?.startsWith("home/hero/") == true ->
                focusAfterRefresh(lastDockFocus, dockTargets)
            key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && lastFocus == "home/search" ->
                heroTarget ?: focusAfterRefresh(lastDockFocus, dockTargets)
            else -> null
        }
        if (target != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
            if (target.startsWith("home/hero/") && homeGrid.canScrollBackward) {
                scope.launch {
                    homeGrid.scrollToItem(0)
                    move = FocusMove(target, ++sequence)
                }
            } else move = FocusMove(target, ++sequence)
        }
        target != null
    }) {
        val margin = (maxWidth * .055f).coerceIn(24.dp, 72.dp)
        val compact = maxHeight < 480.dp
        val vertical = if (compact) 18.dp else 24.dp
        val headerSpace = vertical + 65.dp
        val columns = if (maxWidth >= 940.dp) 6 else 5
        val gridTopPadding = 8.dp
        val homeRowSpacing = 20.dp
        val dockTopPadding = 6.dp
        val dockHeight = if (compact) 88.dp else 106.dp
        val spaceOutsideHero = headerSpace + gridTopPadding + homeRowSpacing + dockTopPadding + dockHeight
        val heroHeight = (maxHeight - spaceOutsideHero - 12.dp).coerceAtLeast(126.dp)
        val density = LocalDensity.current
        val scrollThreshold = with(density) { 40.dp.roundToPx() }
        val focusGutter = with(density) { 10.dp.toPx() }
        val homeFocusScroll = remember(focusGutter) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    if (size + 2 * focusGutter > containerSize) {
                        return super.calculateScrollDistance(offset, size, containerSize)
                    }
                    return when {
                        offset < focusGutter -> offset - focusGutter
                        offset + size > containerSize - focusGutter -> offset + size + focusGutter - containerSize
                        else -> 0f
                    }
                }
            }
        }
        val browsing by remember(grid, scrollThreshold) {
            derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > scrollThreshold }
        }

        val backdropFrame = if (hasStage) {
            Modifier.fillMaxWidth().height((spaceOutsideHero + heroHeight).coerceAtMost(maxHeight))
        } else Modifier.fillMaxSize()
        SceneBackdrop(backdrop.takeIf { covers.isNotEmpty() }, backdropFrame)
        if (hero != null) Box(backdropFrame.background(Brush.horizontalGradient(
            0f to Color(0xAD000000), .36f to Color(0x47000000), .72f to Color.Transparent,
        )))
        Box(backdropFrame.background(Brush.verticalGradient(
            0f to Color(0xA0000000), .22f to Color(0x30000000), .45f to Color.Transparent,
            .66f to Color(0x4F000000), .87f to Color(0xDB101010), 1f to ink,
        )))
        if (browsing) Box(Modifier.fillMaxSize().background(Color(0xAE000000)))
        if (appsOpen) {
            AppShelfBackdrop(
                grid, Modifier.fillMaxSize().padding(top = headerSpace, start = margin - 12.dp, end = margin - 12.dp),
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides if (appsOpen) shelfFocusScroll else homeFocusScroll) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(top = headerSpace),
                contentPadding = PaddingValues(start = margin, end = margin, top = gridTopPadding, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(if (appsOpen) 14.dp else homeRowSpacing),
            ) {
                if (hasStage) item(key = "home/hero", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(heroHeight), contentAlignment = Alignment.BottomStart) {
                        if (hero != null) EpisodeHero(
                            media, upcoming, hero, move,
                            focused = { episode ->
                                heroTitleId = episode.title.id
                                onFocused(heroKey(heroTitleId))
                            },
                            open = onHero,
                        ) else if (stageLoading) {
                            Text("Loading titles…", color = secondary, fontSize = 17.sp,
                                modifier = Modifier.padding(bottom = 12.dp).progressSemantics())
                        }
                    }
                }
                item(key = "home/dock", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = dockTopPadding), contentAlignment = Alignment.CenterStart) {
                        FavoritesRow(
                            activity, dock, compact, dockHeight, move, onFocused, Modifier,
                            onApps = {
                                lastFocus = apps.firstOrNull()?.let(::shelfKey) ?: "home/apps"
                                appsOpen = true
                            },
                            onMenu = { appMenu = it },
                        )
                    }
                }
                if (appsOpen) {
                    item(key = SHELF_HEADING, span = { GridItemSpan(maxLineSpan) }) {
                        Text("All apps", fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp))
                    }
                    items(apps, key = ::shelfKey) { app ->
                        AppTile(
                            activity, app, shelfKey(app), move, onFocused, if (compact) 86.dp else 96.dp,
                            onClick = { activity.open(app) }, onLong = { appMenu = app }, flat = true,
                        )
                    }
                    if (apps.isEmpty()) item(key = "apps/empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("Apps you install will appear here.", color = secondary, fontSize = 16.sp)
                    }
                } else {
                    for ((key, label, titles) in scenes) {
                        item(key = key, span = { GridItemSpan(maxLineSpan) }) {
                            val library = MediaLibrary.entries.firstOrNull { it.key == key }
                            MediaShelf(
                                media, key, label, titles, move, onFocused, onTitle,
                                onConnect, onRetry = { scope.launch { media.refresh(home = false, library = library, force = true) } },
                                library = library, onSeeAll = { onSearch(library) },
                            )
                        }
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopStart).padding(start = margin, top = vertical).zIndex(1f))
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = margin, top = vertical).zIndex(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocusSurface("home/search", move, onFocused, { onSearch(null) }, Modifier.height(48.dp), glassShape = glassTile) {
                Row(
                    Modifier.fillMaxHeight().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(R.drawable.media_search), null, Modifier.size(20.dp))
                    Text("Search", color = foreground, fontSize = 17.sp)
                }
            }
            HomeAction("home/play", "Get apps", R.drawable.home_play, move, onFocused, activity::getApps)
            HomeAction("home/settings", "TV settings", R.drawable.home_settings, move, onFocused,
                activity::tvSettings, onLongClick = { connectionMenu = true })
        }
    }

    if (connectionMenu) {
        OptionsDialog("30nama connection", null, buildList {
            val connectLabel = if (media.connected) "Pair again" else "Connect 30nama"
            add(Setting("connect", connectLabel, run = onConnect))
            if (media.connected) add(Setting("disconnect", "Disconnect 30nama") {
                scope.launch { media.disconnect() }
            })
        }) { connectionMenu = false }
    }
    appMenu?.let { app -> AppMenu(activity, app) { appMenu = null } }
}

@Composable
private fun AppShelfBackdrop(grid: LazyGridState, modifier: Modifier) {
    Canvas(modifier.clipToBounds()) {
        val heading = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == SHELF_HEADING }
        val radius = 20.dp.toPx()
        val top = heading?.let { it.offset.y.toFloat() - 8.dp.toPx() }
            ?: if (grid.firstVisibleItemIndex >= 2) -radius else return@Canvas
        if (top < size.height) {
            drawRoundRect(
                color = Color(0xEC1C1C1C),
                topLeft = Offset(0f, top),
                size = Size(size.width, size.height - top + radius),
                cornerRadius = CornerRadius(radius),
            )
        }
    }
}

@Composable
private fun SceneBackdrop(artwork: Bitmap?, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val motion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    Crossfade(
        targetState = artwork,
        modifier = modifier.clipToBounds(),
        animationSpec = tween(if (motion) 480 else 0),
        label = "catalog backdrop",
    ) { image ->
        if (image != null) {
            Image(
                image.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Spacer(Modifier.fillMaxSize().background(ink))
        }
    }
}

@Composable
private fun FavoritesRow(
    activity: MainActivity,
    dock: List<TvApp>,
    compact: Boolean,
    height: Dp,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier,
    onApps: () -> Unit,
    onMenu: (TvApp) -> Unit,
) {
    Row(
        modifier.widthIn(max = 820.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        dock.forEach { app ->
            androidx.compose.runtime.key(app.pkg) {
                AppTile(
                    activity, app, dockKey(app), move, onFocused, height,
                    onClick = { activity.open(app) },
                    onLong = { onMenu(app) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(MAX_FAVORITES - dock.size) { Spacer(Modifier.weight(1f)) }
        FocusSurface(
            "home/apps", move, onFocused, onApps, Modifier.weight(1f).height(height),
            glassShape = glassTile,
        ) {
            TileCaption("All apps") {
                Box(Modifier.size(if (compact) 38.dp else 46.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.home_apps), null, Modifier.size(if (compact) 30.dp else 35.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeAction(
    id: String,
    label: String,
    icon: Int,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    FocusSurface(
        id, move, onFocused, onClick, Modifier.size(48.dp),
        onLongClick = onLongClick, glassShape = tileShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), label, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000L * (60 - now.second))
        }
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }
    val style = LocalTextStyle.current.copy(shadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 8f))
    Column(modifier) {
        Text(
            now.format(timeFormat), color = foreground, fontSize = 36.sp, lineHeight = 40.sp,
            fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp, style = style,
        )
        Text(now.format(dateFormat), color = foreground, fontSize = 15.sp, style = style)
    }
}

@Composable
private fun AppTile(
    activity: MainActivity,
    app: TvApp,
    id: String,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    height: Dp,
    onClick: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    val icon by produceState<Bitmap?>(null, app.component) { value = activity.iconFor(app) }
    val bitmap = icon
    FocusSurface(
        id, move, onFocused, onClick,
        modifier.fillMaxWidth().height(height), onLongClick = onLong,
        glassShape = if (flat) null else glassTile,
        fill = if (flat) Color(0xFF2B2B2B) else null,
    ) {
        TileCaption(app.label) {
            if (bitmap != null) {
                val size = Modifier.size(if (height < 90.dp) 38.dp else 46.dp)
                Image(bitmap.asImageBitmap(), null, size.clip(RoundedCornerShape(9.dp)))
            } else {
                Image(painterResource(R.drawable.banana_icon), null, Modifier.size(38.dp))
            }
        }
    }
}

@Composable
private fun TileCaption(label: String, icon: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        icon()
        Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun AppMenu(activity: MainActivity, app: TvApp, close: () -> Unit) {
    val order = activity.pinned.filter { id -> activity.apps.any { it.pkg == id } }
    val position = order.indexOf(app.pkg)
    val items = buildList {
        add(Setting("pin", if (position < 0) "Pin to favorites" else "Unpin from favorites") { activity.togglePin(app) })
        if (position >= 0 && order.size > 1) {
            add(Setting("left", "Move left", position > 0, keepOpen = true) { activity.movePin(app, -1) })
            add(Setting("right", "Move right", position < order.lastIndex, keepOpen = true) { activity.movePin(app, 1) })
        }
        add(Setting("info", "App info") { activity.appInfo(app) })
        if (app.removable) add(Setting("uninstall", "Uninstall") { activity.uninstall(app) })
    }
    val detail = if (position >= 0) "Favorite ${position + 1} of ${order.size}" else null
    OptionsDialog(app.label, detail, items, close)
}

@Composable
private fun OptionsDialog(title: String, detail: String?, items: List<Setting>, close: () -> Unit) {
    val press = remember { MenuSelectPress() }
    var focusedAction by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(.7f)
                .background(panel, dockShape)
                .border(1.dp, edge, dockShape)
                .padding(22.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    when (native.keyCode) {
                        // The menu may receive the repeat or release of the hold that opened it.
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                            press.consume(native.keyCode, native.action == KeyEvent.ACTION_DOWN, native.repeatCount, focusedAction) ||
                                native.isCanceled
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, color = secondary, fontSize = 15.sp) }
            items.forEach { item ->
                androidx.compose.runtime.key(item.key) {
                    Action(item.title, {
                        if (item.enabled) {
                            if (!item.keepOpen) close()
                            item.run()
                        }
                    }, item.key, FocusMove(items.first().key, 0), { focusedAction = it }, Modifier.fillMaxWidth(), item.enabled)
                }
            }
        }
    }
}
