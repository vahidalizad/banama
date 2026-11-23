package tv.scenichome.launcher

import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val ink = Color(0xFF0C201E)
private val panel = Color(0xFF142B29)
private val mint = Color(0xFFC5EBDD)
private val foreground = Color(0xFFF3F8F4)
private val secondary = Color(0xFFBBD5CC)
private val edge = Color(0xFF42615B)
private val dockShape = RoundedCornerShape(24.dp)
private val tileShape = RoundedCornerShape(12.dp)
private val glassTile = RoundedCornerShape(16.dp)
private const val SHELF_HEADING = "apps/heading"
// Keep a visible app at its current position instead of pivoting it toward the top of the TV.
private val shelfFocusScroll = object : BringIntoViewSpec {}

private enum class PhotoDialog { OPTIONS, CREDITS }
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
@OptIn(ExperimentalFoundationApi::class)
internal fun ScenicHome(activity: MainActivity) {
    val grid = rememberLazyGridState()
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    var scrollRequest by remember { mutableIntStateOf(0) }
    var scrolling by remember { mutableStateOf(appsOpen) }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    var photoDialog by remember { mutableStateOf<PhotoDialog?>(null) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }

    val apps = activity.apps
    val dock = remember(apps, activity.pinned, activity.recent) {
        val byId = apps.associateBy { it.pkg }
        dockApps(apps.map { it.pkg }, activity.pinned, activity.recent).mapNotNull(byId::get)
    }
    val scene = activity.scene
    val blur = remember(scene) { scene?.frosted?.asImageBitmap() }
    val showApps = appsOpen || grid.canScrollBackward
    val dialogOpen = appMenu != null || photoDialog != null
    val shelfIds = if (showApps) apps.map(::shelfKey) else emptyList()
    val targets = dock.map(::dockKey) +
        listOf("home/apps", "home/next", "home/play", "home/settings") + shelfIds

    LaunchedEffect(activity.foreground, activity.appsLoaded, scrolling, dialogOpen, targets) {
        if (!activity.foreground || !activity.appsLoaded || scrolling || dialogOpen) return@LaunchedEffect
        val target = focusAfterRefresh(lastFocus, targets) ?: return@LaunchedEffect
        val shelfIndex = shelfIds.indexOf(target)
        if (shelfIndex >= 0) {
            val itemIndex = shelfIndex + 2 // The favorites and heading occupy the first two items.
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) {
                grid.scrollToItem(itemIndex)
            }
        } else if (target.startsWith("home/app") && grid.firstVisibleItemIndex > 0) {
            grid.scrollToItem(0)
        }
        move = FocusMove(target, ++sequence)
    }

    val onFocused: (String) -> Unit = { id ->
        if (!dialogOpen && !scrolling && activity.appsLoaded) lastFocus = id
    }
    BackHandler((appsOpen || grid.canScrollBackward) && !dialogOpen) {
        scrolling = true
        appsOpen = false
    }
    LaunchedEffect(activity.notice) {
        if (activity.notice != null) {
            delay(4_000)
            activity.clearNotice()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = mint, onPrimary = ink, surface = panel, onSurface = foreground,
            background = ink, onBackground = foreground,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(ink)) {
            val margin = (maxWidth * .055f).coerceIn(28.dp, 72.dp)
            val compact = maxHeight < 480.dp
            val vertical = if (compact) 24.dp else 34.dp
            val headerSpace = vertical + 76.dp
            val viewport = (maxHeight - headerSpace).coerceAtLeast(120.dp)
            val reveal = with(LocalDensity.current) { (viewport * .52f).roundToPx() }
            val columns = if (maxWidth >= 940.dp) 6 else 5

            LaunchedEffect(appsOpen, scrollRequest, reveal) {
                try {
                    if (appsOpen) {
                        snapshotFlow { grid.layoutInfo.totalItemsCount }.first { it > 1 }
                        grid.animateScrollToItem(0, reveal)
                        lastFocus = apps.firstOrNull()?.let(::shelfKey) ?: "home/play"
                    } else if (grid.canScrollBackward || scrolling) {
                        grid.animateScrollToItem(0)
                        lastFocus = "home/apps"
                    }
                } finally {
                    scrolling = false
                }
            }

            SceneBackdrop(scene)
            if (showApps) {
                AppShelfBackdrop(
                    grid,
                    Modifier.fillMaxSize().padding(top = headerSpace, start = margin - 12.dp, end = margin - 12.dp),
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides shelfFocusScroll) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.fillMaxSize().padding(top = headerSpace),
                    contentPadding = PaddingValues(start = margin, end = margin, bottom = if (showApps) vertical else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    userScrollEnabled = showApps,
                ) {
                    item(key = "home/dock", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(viewport).padding(bottom = vertical)) {
                            FavoritesRow(
                                activity, dock, blur, compact, move, onFocused,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onApps = {
                                    scrolling = true
                                    appsOpen = true
                                    scrollRequest++
                                },
                                onMenu = { appMenu = it },
                            )
                        }
                    }
                    if (showApps) {
                        item(key = SHELF_HEADING, span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "All apps", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = foreground,
                                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                            )
                        }
                        items(apps, key = ::shelfKey) { app ->
                            AppTile(
                                activity, app, shelfKey(app), move, onFocused, if (compact) 86.dp else 96.dp,
                                onClick = { activity.open(app) }, onLong = { appMenu = app }, flat = true,
                            )
                        }
                        if (apps.isEmpty()) {
                            item(key = "apps/empty", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (activity.appsLoaded) "Apps you install will appear here." else "Finding TV apps…",
                                    color = secondary, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            Clock(blur, Modifier.align(Alignment.TopStart).padding(start = margin, top = vertical).zIndex(1f))
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = margin, top = vertical).zIndex(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeAction(
                    "home/next", "Next wallpaper", R.drawable.home_next, move, onFocused, blur,
                    onClick = activity::nextPhoto, onLongClick = { photoDialog = PhotoDialog.OPTIONS },
                )
                HomeAction("home/play", "Get apps", R.drawable.home_play, move, onFocused, blur, activity::getApps)
                HomeAction("home/settings", "TV settings", R.drawable.home_settings, move, onFocused, blur, activity::tvSettings)
            }
            activity.notice?.let { message ->
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(panel, RoundedCornerShape(16.dp))
                        .border(1.dp, edge, RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) { Text(message, color = foreground, fontSize = 15.sp) }
            }
        }

        appMenu?.let { app -> AppMenu(activity, app) { appMenu = null } }
        when (photoDialog) {
            PhotoDialog.OPTIONS -> OptionsDialog(
                "Wallpaper", null, listOf(
                    Setting("pause", if (activity.paused) "Resume rotation" else "Pause rotation", keepOpen = true) {
                        activity.togglePause()
                    },
                    Setting("credits", "Photo credits") { photoDialog = PhotoDialog.CREDITS },
                ),
            ) { photoDialog = null }
            PhotoDialog.CREDITS -> {
                val credit = scene?.scene ?: Scene.fallback
                OptionsDialog(
                    "Photo credits",
                    "${credit.title}\nPhotograph by ${credit.artist}\nWikimedia Commons · ${credit.license}",
                    listOf(Setting("source", "Open photo source", run = activity::openPhotoSource)),
                ) { photoDialog = null }
            }
            null -> Unit
        }
    }
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
                color = Color(0xEC15191E),
                topLeft = Offset(0f, top),
                size = Size(size.width, size.height - top + radius),
                cornerRadius = CornerRadius(radius),
            )
        }
    }
}

@Composable
private fun SceneBackdrop(scene: LoadedScene?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val motion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    Crossfade(
        targetState = scene,
        animationSpec = tween(if (motion) 480 else 0),
        label = "scenic photograph",
    ) { image ->
        if (image != null) {
            Image(
                image.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
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
    blur: ImageBitmap?,
    compact: Boolean,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    modifier: Modifier,
    onApps: () -> Unit,
    onMenu: (TvApp) -> Unit,
) {
    val height = if (compact) 88.dp else 106.dp
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
                    blur = blur,
                )
            }
        }
        repeat(MAX_FAVORITES - dock.size) { Spacer(Modifier.weight(1f)) }
        FocusSurface(
            "home/apps", move, onFocused, onApps, Modifier.weight(1f).height(height),
            glassShape = glassTile, blur = blur,
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
    blur: ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    FocusSurface(
        id, move, onFocused, onClick, Modifier.size(48.dp),
        onLongClick = onLongClick, glassShape = tileShape, blur = blur,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), label, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Clock(photo: ImageBitmap?, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000L * (60 - now.second))
        }
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }
    PhotoText(photo, modifier) { color, style ->
        Column {
            Text(
                now.format(timeFormat), color = color, fontSize = 36.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp, style = style,
            )
            Text(now.format(dateFormat), color = color, fontSize = 15.sp, style = style)
        }
    }
}

@Composable
private fun PhotoText(
    photo: ImageBitmap?,
    modifier: Modifier,
    content: @Composable (Color, TextStyle) -> Unit,
) {
    var area by remember { mutableStateOf<PhotoArea?>(null) }
    val luminance = remember(photo, area) { area?.let { photo?.let(it::luminance) } ?: (0f..0f) }
    val darkText = Color(0xFF10171E)
    val darkContrast = (luminance.start + .05f) / (darkText.luminance() + .05f)
    val lightContrast = (foreground.luminance() + .05f) / (luminance.endInclusive + .05f)
    val dark = darkContrast > lightContrast
    val color = if (dark) darkText else foreground
    val shadow = if (dark) Color(0x64FFFFFF) else Color(0xAA050B11)
    val style = LocalTextStyle.current.copy(shadow = Shadow(shadow, Offset(0f, 2f), 8f))
    Box(modifier.onGloballyPositioned { area = it.photoArea() }) { content(color, style) }
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
    blur: ImageBitmap? = null,
    flat: Boolean = false,
) {
    val icon by produceState<Bitmap?>(null, app.component) { value = activity.iconFor(app) }
    val bitmap = icon
    FocusSurface(
        id, move, onFocused, onClick,
        modifier.fillMaxWidth().height(height), onLongClick = onLong,
        glassShape = if (flat) null else glassTile,
        blur = blur,
        fill = if (flat) Color(0xFF292F35) else null,
    ) {
        TileCaption(app.label) {
            if (bitmap != null) {
                val size = Modifier.size(if (height < 90.dp) 38.dp else 46.dp)
                Image(bitmap.asImageBitmap(), null, size.clip(RoundedCornerShape(9.dp)))
            } else {
                Image(painterResource(R.drawable.scenic_icon), null, Modifier.size(38.dp))
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
        Box(Modifier.fillMaxHeight().padding(horizontal = 15.dp), contentAlignment = Alignment.CenterStart) {
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
    blur: ImageBitmap? = null,
    fill: Color? = null,
    content: @Composable () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val shape = glassShape ?: tileShape
    val focusedFill = if (fill != null) Color(0xFFE1E8EF) else mint
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
            containerColor = if (glassShape != null) Color.Transparent else fill ?: Color(0xFF213D38),
            contentColor = foreground,
            focusedContainerColor = if (glassShape != null) Color.Transparent else focusedFill,
            focusedContentColor = if (glassShape != null) foreground else ink,
            pressedContainerColor = if (glassShape != null) Color(0x35EAF6FF) else focusedFill,
            pressedContentColor = if (glassShape != null) foreground else ink,
            disabledContainerColor = panel, disabledContentColor = secondary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (glassShape != null) {
                Border(BorderStroke(.75.dp, Color(0x38FFFFFF)), shape = shape)
            } else Border.None,
            focusedBorder = Border(
                BorderStroke(if (glassShape != null) 1.5.dp else 2.dp, Color(0xFFF1FFF9)), shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (glassShape != null) 1.045f else 1.025f),
    ) {
        if (glassShape != null) {
            FrostedGlass(blur, shape, Modifier.matchParentSize())
        }
        content()
    }
}

@Composable
private fun FrostedGlass(photo: ImageBitmap?, shape: Shape, modifier: Modifier) {
    var area by remember { mutableStateOf<PhotoArea?>(null) }
    Box(modifier.clip(shape).onGloballyPositioned { area = it.photoArea() }) {
        Crossfade(photo, Modifier.matchParentSize(), animationSpec = tween(480), label = "frosted wallpaper") { image ->
            val frame = area
            if (image != null && frame != null) {
                val crop = frame.imageRegion(image)
                val tint = remember(image, frame) { glassTint(frame.luminance(image).endInclusive) }
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(crop.left, crop.top),
                        srcSize = crop.size,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    drawRect(Color.Black.copy(alpha = tint))
                }
            } else {
                Spacer(Modifier.fillMaxSize().background(Color(0xAB131B24)))
            }
        }
    }
}

private data class PhotoArea(val origin: Offset, val size: IntSize, val viewport: IntSize) {
    fun imageRegion(image: ImageBitmap): IntRect {
        val scale = maxOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val left = (origin.x - (viewport.width - image.width * scale) / 2f) / scale
        val top = (origin.y - (viewport.height - image.height * scale) / 2f) / scale
        val x = floor(left).toInt().coerceIn(0, image.width - 1)
        val y = floor(top).toInt().coerceIn(0, image.height - 1)
        return IntRect(
            x, y,
            ceil(left + size.width / scale).toInt().coerceIn(x + 1, image.width),
            ceil(top + size.height / scale).toInt().coerceIn(y + 1, image.height),
        )
    }

    fun luminance(image: ImageBitmap): ClosedFloatingPointRange<Float> {
        val crop = imageRegion(image)
        val bitmap = image.asAndroidBitmap()
        var lightest = 0f
        var darkest = 1f
        for (row in 0..4) {
            val sourceY = crop.top + (crop.height - 1) * row / 4
            for (column in 0..4) {
                val sourceX = crop.left + (crop.width - 1) * column / 4
                val value = android.graphics.Color.luminance(bitmap.getPixel(sourceX, sourceY))
                lightest = maxOf(lightest, value)
                darkest = minOf(darkest, value)
            }
        }
        return darkest..lightest
    }
}

private fun LayoutCoordinates.photoArea(): PhotoArea? {
    if (!isAttached) return null
    val viewport = findRootCoordinates().size
    return if (viewport.width > 0 && viewport.height > 0) {
        PhotoArea(positionInRoot(), size, viewport)
    } else null
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
