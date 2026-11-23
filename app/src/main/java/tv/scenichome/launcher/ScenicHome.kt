package tv.scenichome.launcher

import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

private enum class Page { HOME, APPS, SETTINGS, MANAGE, CREDITS }
private data class FocusMove(val id: String, val revision: Int)
private data class Setting(val key: String, val title: String, val enabled: Boolean = true, val run: () -> Unit)
private val appKey: (TvApp) -> String = { "app/${it.pkg}" }

@Composable
internal fun ScenicHome(activity: MainActivity) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var menu by remember { mutableStateOf<TvApp?>(null) }
    var lastHome by rememberSaveable { mutableStateOf<String?>(null) }
    val remembered = remember { mutableMapOf<Page, String>() }
    val grid = rememberLazyGridState()
    val settingsList = rememberLazyListState()
    var sequence by remember { mutableIntStateOf(0) }
    var move by remember { mutableStateOf<FocusMove?>(null) }

    val apps = activity.apps
    val dock = remember(apps, activity.pinned, activity.recent) {
        val byId = apps.associateBy { it.pkg }
        dockApps(apps.map { it.pkg }, activity.pinned, activity.recent).mapNotNull(byId::get)
    }
    val settings = listOf(
        Setting("gallery/next", if (activity.photoBusy) "Loading photo…" else "Next photo", !activity.photoBusy) {
            activity.nextPhoto()
        },
        Setting("gallery/pause", if (activity.paused) "Resume rotation" else "Pause rotation") {
            activity.togglePause()
        },
        Setting("gallery/credits", "Photo credits") { page = Page.CREDITS },
        Setting("system/manage", "Manage apps") { page = Page.MANAGE },
        Setting("system/play", "Get apps", run = activity::getApps),
        Setting("system/settings", "TV settings", run = activity::tvSettings),
    )
    val targets = when (page) {
        Page.HOME -> dock.map(appKey) + listOf("home/apps", "home/play", "home/settings")
        Page.APPS -> apps.map(appKey).ifEmpty { listOf("apps/play") }
        Page.MANAGE -> apps.map(appKey).ifEmpty { listOf("apps/play") }
        Page.SETTINGS -> settings.map { it.key }
        Page.CREDITS -> listOf("credits/source")
    }

    LaunchedEffect(page, activity.foreground, activity.appsLoaded, menu != null, targets) {
        if (!activity.foreground || menu != null) return@LaunchedEffect
        if (page == Page.HOME && !activity.appsLoaded) return@LaunchedEffect
        val old = if (page == Page.HOME) lastHome else remembered[page]
        val target = focusAfterRefresh(old, targets) ?: return@LaunchedEffect
        val index = targets.indexOf(target)
        if (page == Page.APPS || page == Page.MANAGE) {
            if (apps.isNotEmpty() && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                grid.scrollToItem(index)
            }
        }
        if (page == Page.SETTINGS && settingsList.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            settingsList.scrollToItem(index)
        }
        move = FocusMove(target, ++sequence)
    }

    val onFocused: (String) -> Unit = { id ->
        if (menu == null) {
            if (page == Page.HOME) {
                if (activity.appsLoaded) lastHome = id
            } else {
                remembered[page] = id
            }
        }
    }
    BackHandler(page != Page.HOME && menu == null) {
        page = when (page) {
            Page.CREDITS, Page.MANAGE -> Page.SETTINGS
            else -> Page.HOME
        }
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

            SceneBackdrop(activity.scene)
            when (page) {
                Page.HOME -> Home(
                    activity, dock, margin, vertical, compact, move, onFocused,
                    onApps = { page = Page.APPS },
                    onSettings = { page = Page.SETTINGS },
                    onMenu = { menu = it },
                )
                Page.APPS, Page.MANAGE -> {
                    val managing = page == Page.MANAGE
                    Sheet(
                        if (managing) "Manage apps" else "All apps",
                        if (managing) "Choose an app for options" else "Hold OK for app options",
                        margin, vertical,
                    ) {
                        if (apps.isEmpty()) {
                            Text(
                                if (activity.appsLoaded) "Apps you install will appear here." else "Finding TV apps…",
                                color = secondary, fontSize = 16.sp,
                            )
                            Action("Get apps", activity::getApps, "apps/play", move, onFocused)
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(if (maxWidth >= 940.dp) 6 else 5),
                                state = grid,
                                modifier = Modifier.fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(apps, key = { it.pkg }) { app ->
                                    AppTile(
                                        activity, app, move, onFocused,
                                        if (compact) 86.dp else 100.dp,
                                        onClick = if (managing) ({ menu = app }) else ({ activity.open(app) }),
                                        onLong = { menu = app },
                                    )
                                }
                            }
                        }
                    }
                }
                Page.SETTINGS -> Sheet("Settings", null, margin, vertical) {
                    LazyColumn(
                        state = settingsList,
                        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(settings, key = { it.key }) { item ->
                            Action(item.title, item.run, item.key, move, onFocused, Modifier.fillMaxWidth(), item.enabled)
                        }
                    }
                }
                Page.CREDITS -> {
                    val scene = activity.scene?.scene ?: Scene.fallback
                    Sheet("Photo credits", null, margin, vertical) {
                        Column(
                            Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(scene.title, fontSize = 25.sp, fontWeight = FontWeight.Medium, color = foreground)
                            Text("Photograph by ${scene.artist}", fontSize = 17.sp, color = secondary)
                            Text("Wikimedia Commons · ${scene.license}", fontSize = 16.sp, color = secondary)
                            Action("Open photo source", activity::openPhotoSource, "credits/source", move, onFocused)
                        }
                    }
                }
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

        menu?.let { app ->
            AppMenu(activity, app) { menu = null }
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
private fun Home(
    activity: MainActivity,
    dock: List<TvApp>,
    margin: Dp,
    vertical: Dp,
    compact: Boolean,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    onApps: () -> Unit,
    onSettings: () -> Unit,
    onMenu: (TvApp) -> Unit,
) {
    val tileHeight = if (compact) 88.dp else 106.dp
    val scene = activity.scene
    val blur = remember(scene) { scene?.frosted?.asImageBitmap() }
    Box(Modifier.fillMaxSize().padding(horizontal = margin, vertical = vertical)) {
        Clock(blur, Modifier.align(Alignment.TopStart))
        Row(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 820.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            dock.forEach { app ->
                androidx.compose.runtime.key(app.pkg) {
                    AppTile(
                        activity, app, move, onFocused, tileHeight,
                        onClick = { activity.open(app) },
                        onLong = { onMenu(app) },
                        modifier = Modifier.weight(1f),
                        glass = true,
                        blur = blur,
                    )
                }
            }
            repeat(MAX_FAVORITES - dock.size) { Spacer(Modifier.weight(1f)) }
            FocusSurface(
                "home/apps", move, onFocused, onApps, Modifier.weight(1f).height(tileHeight),
                glassShape = glassTile,
                blur = blur,
            ) {
                TileCaption("All apps") {
                    Box(Modifier.size(if (compact) 38.dp else 46.dp), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.home_apps), null, Modifier.size(if (compact) 30.dp else 35.dp))
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeAction("home/play", "Get apps", R.drawable.home_play, move, onFocused, blur, activity::getApps)
            HomeAction("home/settings", "Settings", R.drawable.home_settings, move, onFocused, blur, onSettings)
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
) {
    FocusSurface(id, move, onFocused, onClick, Modifier.size(48.dp), glassShape = tileShape, blur = blur) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), label, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Clock(photo: ImageBitmap?, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var area by remember { mutableStateOf<PhotoArea?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000L * (60 - now.second))
        }
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }
    val luminance = remember(photo, area) { area?.let { photo?.let(it::luminance) } ?: (0f..0f) }
    val darkText = Color(0xFF10171E)
    val darkContrast = (luminance.start + .05f) / (darkText.luminance() + .05f)
    val lightContrast = (foreground.luminance() + .05f) / (luminance.endInclusive + .05f)
    val dark = darkContrast > lightContrast
    val color = if (dark) darkText else foreground
    val shadow = if (dark) Color(0x64FFFFFF) else Color(0xAA050B11)
    val legibleOnPhoto = LocalTextStyle.current.copy(shadow = Shadow(shadow, Offset(0f, 2f), 8f))
    Column(modifier.onGloballyPositioned { area = it.photoArea() }) {
        Text(
            now.format(timeFormat), color = color, fontSize = 36.sp, lineHeight = 40.sp,
            fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp, style = legibleOnPhoto,
        )
        Text(now.format(dateFormat), color = color, fontSize = 15.sp, style = legibleOnPhoto)
    }
}

@Composable
private fun Sheet(
    title: String,
    detail: String?,
    margin: Dp,
    vertical: Dp,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xEB0D211F))
            .padding(horizontal = margin, vertical = vertical),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 38.sp, color = foreground, fontWeight = FontWeight.SemiBold, lineHeight = 44.sp)
            detail?.let { Text(it, color = secondary, fontSize = 16.sp) }
        }
        content()
    }
}

@Composable
private fun AppTile(
    activity: MainActivity,
    app: TvApp,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    height: Dp,
    onClick: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    blur: ImageBitmap? = null,
) {
    val icon by produceState<Bitmap?>(null, app.component) { value = activity.iconFor(app) }
    val bitmap = icon
    FocusSurface(
        appKey(app), move, onFocused, onClick,
        modifier.fillMaxWidth().height(height), onLongClick = onLong,
        glassShape = if (glass) glassTile else null,
        blur = blur,
    ) {
        TileCaption(app.label) {
            if (bitmap != null) {
                val size = Modifier.size(if (height < 90.dp) 38.dp else 46.dp)
                Image(bitmap.asImageBitmap(), null, if (glass) size.clip(RoundedCornerShape(9.dp)) else size)
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
    content: @Composable () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val shape = glassShape ?: tileShape
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
            containerColor = if (glassShape != null) Color.Transparent else Color(0xFF213D38),
            contentColor = foreground,
            focusedContainerColor = if (glassShape != null) Color.Transparent else mint,
            focusedContentColor = if (glassShape != null) foreground else ink,
            pressedContainerColor = if (glassShape != null) Color(0x35EAF6FF) else Color(0xFFABD8C7),
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
                val crop = frame.wallpaperCrop(image)
                val tint = remember(image, frame) { glassTint(frame.luminance(image).endInclusive) }
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(
                            (crop.left - frame.origin.x).roundToInt(),
                            (crop.top - frame.origin.y).roundToInt(),
                        ),
                        dstSize = IntSize(crop.width.roundToInt(), crop.height.roundToInt()),
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
    fun wallpaperCrop(image: ImageBitmap): Rect {
        val scale = maxOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val width = (image.width * scale).roundToInt().toFloat()
        val height = (image.height * scale).roundToInt().toFloat()
        val left = (viewport.width - width) / 2f
        val top = (viewport.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    fun luminance(image: ImageBitmap): ClosedFloatingPointRange<Float> {
        val crop = wallpaperCrop(image)
        val bitmap = image.asAndroidBitmap()
        var lightest = 0f
        var darkest = 1f
        for (row in 0..4) {
            val y = origin.y + size.height * row / 4f
            val sourceY = ((y - crop.top) / crop.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0..4) {
                val x = origin.x + size.width * column / 4f
                val sourceX = ((x - crop.left) / crop.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
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
    val press = remember(app.pkg) { MenuSelectPress() }
    var focusedAction by remember(app.pkg) { mutableStateOf<String?>(null) }
    val items = buildList {
        add(Setting("pin", if (position < 0) "Pin to favorites" else "Unpin from favorites") { activity.togglePin(app) })
        if (position >= 0 && order.size > 1) {
            add(Setting("left", "Move left", position > 0) { activity.movePin(app, -1) })
            add(Setting("right", "Move right", position < order.lastIndex) { activity.movePin(app, 1) })
        }
        add(Setting("info", "App info") { activity.appInfo(app) })
        if (app.removable) add(Setting("uninstall", "Uninstall") { activity.uninstall(app) })
    }
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
            Text(app.label, color = foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (position >= 0) {
                Text("Favorite ${position + 1} of ${order.size}", color = secondary, fontSize = 15.sp)
            }
            items.forEach { item ->
                androidx.compose.runtime.key(item.key) {
                    Action(item.title, {
                        if (item.enabled) {
                            if (item.key != "left" && item.key != "right") close()
                            item.run()
                        }
                    }, item.key, FocusMove("pin", 0), { focusedAction = it }, Modifier.fillMaxWidth(), item.enabled)
                }
            }
        }
    }
}
