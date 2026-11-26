package tv.scenichome.home

import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private val ink = Color(0xFF101010)
private val panel = Color(0xFF1C1C1C)
private val focusColor = Color(0xFFF1F1F1)
private val foreground = Color(0xFFF5F5F5)
private val secondary = Color(0xFFCBCBCB)
private val edge = Color(0xFF585858)
private val dockShape = RoundedCornerShape(24.dp)
private val tileShape = RoundedCornerShape(12.dp)
private val glassTile = RoundedCornerShape(16.dp)
private val mutedIcon = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.2f) })
private data class FocusMove(val id: String, val revision: Int)
private data class Setting(
    val key: String,
    val title: String,
    val enabled: Boolean = true,
    val keepOpen: Boolean = false,
    val run: () -> Unit,
)

private fun dockKey(app: TvApp) = "home/app/${app.pkg}"
private fun shelfKey(app: TvApp) = "apps/app/${app.pkg}"
@Composable
internal fun ScenicHome(activity: MainActivity) {
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
        CompositionLocalProvider(LocalContentColor provides foreground) { LauncherScreen(activity) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LauncherScreen(activity: MainActivity) {
    val grid = rememberLazyGridState()
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    var settings by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var lastDockFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var focusOnSwitch by remember { mutableStateOf<String?>(null) }
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }

    val apps = activity.apps
    val dock = remember(apps, activity.pinned, activity.recent) {
        val byId = apps.associateBy(TvApp::pkg)
        dockApps(apps.map(TvApp::pkg), activity.pinned, activity.recent).mapNotNull(byId::get)
    }
    val headerTargets = listOf("home/play", "home/settings")
    val dockTargets = dock.map(::dockKey) + "home/apps"
    val targets = (if (appsOpen) apps.map(::shelfKey) else dockTargets) + headerTargets
    val dialogOpen = appMenu != null || settings || credits
    val slide = commonsSlide(activity, !appsOpen && !dialogOpen)

    LaunchedEffect(activity.foreground, activity.appsLoaded, appsOpen, dialogOpen, targets) {
        if (!activity.foreground || !activity.appsLoaded || dialogOpen) return@LaunchedEffect
        val target = focusAfterRefresh(focusOnSwitch ?: lastFocus, targets) ?: return@LaunchedEffect
        if (appsOpen) {
            val item = apps.indexOfFirst { shelfKey(it) == target }
            if (item >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == item + 1 }) {
                grid.scrollToItem(item + 1)
            }
        }
        move = FocusMove(target, ++sequence)
        focusOnSwitch = null
    }
    val focused: (String) -> Unit = { id ->
        if (!dialogOpen && activity.appsLoaded) {
            lastFocus = id
            if (id in dockTargets) lastDockFocus = id
        }
    }
    BackHandler(!dialogOpen && (appsOpen || activity.keepHomeOnBack)) {
        if (appsOpen) {
            focusOnSwitch = "home/apps"
            appsOpen = false
        } else {
            move = FocusMove(dockTargets.first(), ++sequence)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ink).onPreviewKeyEvent {
        val key = it.nativeKeyEvent
        val target = when {
            dialogOpen || appsOpen -> null
            key.keyCode == KeyEvent.KEYCODE_DPAD_UP && lastFocus in dockTargets -> "home/settings"
            key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && lastFocus in headerTargets ->
                focusAfterRefresh(lastDockFocus, dockTargets)
            else -> null
        }
        if (target != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
            move = FocusMove(target, ++sequence)
        }
        target != null
    }) {
        val margin = (maxWidth * .055f).coerceIn(24.dp, 72.dp)
        val compact = maxHeight < 480.dp
        val top = if (compact) 18.dp else 26.dp

        SceneBackdrop(slide?.image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color(0xA0000000), .30f to Color(0x24000000), .57f to Color(0x10000000),
            1f to Color(0xEC101010),
        )))
        if (appsOpen) {
            Box(Modifier.fillMaxSize().background(ink.copy(alpha = .90f)))
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (maxWidth >= 940.dp) 6 else 5),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(top = top + 75.dp),
                contentPadding = PaddingValues(start = margin, end = margin, top = 5.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "apps/heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text("All apps", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                items(apps, key = ::shelfKey) { app ->
                    AppTile(
                        activity, app, shelfKey(app), move, focused, if (compact) 86.dp else 96.dp,
                        onClick = { activity.open(app) }, onLong = { appMenu = app }, flat = true,
                    )
                }
                if (apps.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Apps you install will appear here.", color = secondary, fontSize = 16.sp)
                }
            }
        } else {
            FavoritesRow(
                activity, dock, compact, lastFocus, move, focused,
                Modifier.align(Alignment.BottomCenter).padding(start = margin, end = margin, bottom = 34.dp),
                onApps = {
                    focusOnSwitch = apps.firstOrNull()?.let(::shelfKey) ?: "home/settings"
                    appsOpen = true
                },
                onMenu = { appMenu = it },
            )
        }
        Clock(Modifier.align(Alignment.TopStart).padding(start = margin, top = top))
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = margin, top = top),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeAction("home/play", "Get apps", R.drawable.home_play, move, focused, activity::getApps)
            HomeAction("home/settings", "Launcher settings", R.drawable.home_settings, move, focused) {
                settings = true
            }
        }
        activity.notice?.let { message ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp)
                    .background(panel, RoundedCornerShape(12.dp)).border(1.dp, edge, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            ) { Text(message, color = foreground, fontSize = 15.sp) }
        }
    }

    if (settings) {
        OptionsDialog("Launcher settings", activity.homeStatus, buildList {
            add(Setting("tv-settings", "TV settings", run = activity::tvSettings))
            add(Setting("default-home", "Default Home app", run = activity::defaultHomeSettings))
            if (BuildConfig.DEBUG) add(Setting("home-fallback", "Home button fallback", run = activity::homeButtonSettings))
            add(Setting("credits", "Photo credits") { credits = true })
        }) { settings = false }
    }
    if (credits) {
        PhotoCredits(slide?.photo, activity::openPhotoLink) { credits = false }
    }
    appMenu?.let { app -> AppMenu(activity, app) { appMenu = null } }
}

@Composable
private fun PhotoCredits(photo: CommonsPhoto?, openLink: (String) -> Unit, close: () -> Unit) {
    val author = photo?.let {
        android.text.Html.fromHtml(it.artist, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }
    val detail = photo?.let {
        "${it.title}\n$author · ${it.license}\nWikimedia Commons · Cropped"
    } ?: "Photos will appear when Wikimedia Commons is available."
    val items = buildList {
        if (photo != null) {
            add(Setting("source", "Open source photo") { openLink(photo.sourceUrl) })
            photo.licenseUrl?.let { url ->
                add(Setting("license", "Open license") { openLink(url) })
            }
        }
        add(Setting("close", "Close", run = close))
    }
    OptionsDialog("Photo credits", detail, items, close)
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
        label = "Commons backdrop",
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
    focusedId: String?,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier,
    onApps: () -> Unit,
    onMenu: (TvApp) -> Unit,
) {
    val height = if (compact) 80.dp else 88.dp
    val iconSize = if (compact) 44.dp else 52.dp
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        dock.forEach { app ->
            androidx.compose.runtime.key(app.pkg) {
                AppTile(
                    activity, app, dockKey(app), move, onFocused, height,
                    onClick = { activity.open(app) },
                    onLong = { onMenu(app) },
                    modifier = Modifier.weight(1f),
                    iconSize = iconSize,
                    muted = focusedId != dockKey(app),
                )
            }
        }
        repeat(MAX_FAVORITES - dock.size) { Spacer(Modifier.weight(1f)) }
        FocusSurface(
            "home/apps", move, onFocused, onApps, Modifier.weight(1f).height(height),
            glassShape = glassTile,
        ) {
            TileCaption("All apps") {
                Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.home_apps), null, Modifier.size(if (compact) 32.dp else 38.dp))
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
) {
    FocusSurface(
        id, move, onFocused, onClick, Modifier.size(48.dp),
        glassShape = tileShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), label, Modifier.size(24.dp), colorFilter = mutedIcon)
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
    iconSize: Dp = if (height < 90.dp) 38.dp else 46.dp,
    muted: Boolean = false,
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
            val painter = if (bitmap != null) {
                remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
            } else {
                painterResource(R.drawable.banana_icon)
            }
            Image(
                painter, null, Modifier.size(iconSize).clip(RoundedCornerShape(9.dp)),
                alpha = if (muted) .78f else 1f,
                colorFilter = if (muted) mutedIcon else null,
            )
        }
    }
}

@Composable
private fun TileCaption(label: String, icon: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        icon()
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Action(
    title: String,
    onClick: () -> Unit,
    id: String,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FocusSurface(id, move, onFocused, onClick, modifier.height(48.dp), enabled = enabled) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FocusSurface(
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (transparentFocus) 1f else 1.025f),
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
    var focusedAction by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(.7f)
                .background(panel, dockShape)
                .border(1.dp, edge, dockShape)
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
                .guardDialogSelection { focusedAction },
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

@Composable
private fun Modifier.guardDialogSelection(focusedAction: () -> String?): Modifier {
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
