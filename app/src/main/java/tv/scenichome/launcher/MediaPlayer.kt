package tv.scenichome.launcher

import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

private enum class Source { HLS, FIND_DOWNLOAD, DOWNLOAD, HD, RENEW_STREAM, UNAVAILABLE }

private data class TextTrack(val group: Tracks.Group, val index: Int, val name: String, val preference: String)
private data class VideoQuality(val source: Source, val height: Int = 0) {
    val label get() = when (source) {
        Source.DOWNLOAD -> "4K Download"
        Source.HLS -> "4K Stream"
        else -> "${height}p"
    }
}

private const val SEEK_JUMP = 10_000L
private const val SUBTITLE_TEXT_FRACTION = .045f

internal fun subtitleBottomInsets(viewHeight: Int, subtitleHeight: Int, overlayHeight: Int, gap: Int): Pair<Int, Float> {
    if (subtitleHeight <= 0) return 0 to .12f
    val letterbox = (viewHeight - subtitleHeight).coerceAtLeast(0) / 2
    val overlayInset = if (overlayHeight > 0) overlayHeight - letterbox + gap else 0
    // View padding also lifts cues that come with an authored line position.
    val inset = maxOf((subtitleHeight * .04f).roundToInt(), overlayInset)
        .coerceAtMost((subtitleHeight * .55f).toInt())
    val extra = (subtitleHeight * .12f - inset).coerceAtLeast(0f) / (subtitleHeight - inset)
    return inset to extra
}

private fun Tracks.Group.videoHeight(index: Int): Int? {
    val format = getTrackFormat(index)
    if (!isTrackSupported(index) || format.width <= 0 || format.height <= 0) return null
    return if (isUhd(format.width, format.height)) 2160 else format.height
}

private fun Tracks.videoQualities(): List<Int> =
    groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).mapNotNull(group::videoHeight) }.distinct().sortedDescending()

private fun Tracks.videoOverride(source: Source, hdHeight: Int?): TrackSelectionOverride? =
    groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { index ->
            val height = group.videoHeight(index) ?: return@filter false
            if (source == Source.HD) height < 2160 && (hdHeight == null || hdHeight == height) else height == 2160
        }.map { group to it }
    }.maxWithOrNull(compareBy(
        { (group, index) -> group.videoHeight(index) ?: 0 },
        { (group, index) -> group.getTrackFormat(index).bitrate },
        { (group, index) -> group.getTrackFormat(index).width },
    ))?.let { (group, index) -> TrackSelectionOverride(group.mediaTrackGroup, index) }

private fun Caption.subtitle(): MediaItem.SubtitleConfiguration {
    val path = httpsUri(url)?.path.orEmpty().lowercase(Locale.ROOT)
    val mime = when {
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLanguage(language).build()
}

internal fun subtitlePreferenceKey(language: String?, name: String): String {
    val primary = language.orEmpty().trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    return when (primary) {
        "eng" -> "en"
        "fas", "per" -> "fa"
        "", "und" -> when (name.trim().lowercase(Locale.ROOT).takeWhile(Char::isLetter)) {
            "english" -> "en"
            "persian", "farsi", "فارسی", "فارسي" -> "fa"
            else -> "label:$name"
        }
        else -> primary
    }
}

private fun Tracks.textTracks(): List<TextTrack> = groups.flatMap { group ->
    if (group.type != C.TRACK_TYPE_TEXT) return@flatMap emptyList()
    (0 until group.length).filter(group::isTrackSupported).map { index ->
        val format = group.getTrackFormat(index)
        val name = format.label ?: format.language?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        } ?: "Subtitle ${index + 1}"
        TextTrack(group, index, name, subtitlePreferenceKey(format.language, name))
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerScreen(
    activity: MainActivity,
    title: MediaTitle,
    catalog: PlaybackCatalog,
    episode: MediaEpisode,
    onEpisode: (MediaEpisode) -> Unit,
    onClose: () -> Unit,
) {
    val player = remember {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        ExoPlayer.Builder(activity).build()
    }
    var source by remember(episode) { mutableStateOf(if (episode.hls == null) Source.FIND_DOWNLOAD else Source.HLS) }
    var retainedHls by remember(episode) { mutableStateOf(false) }
    var streamEpisode by remember(episode) { mutableStateOf(episode) }
    var renewedSource by remember(episode) { mutableStateOf<Source?>(null) }
    var renewDownloads by remember(episode) { mutableStateOf(false) }
    var candidate by remember(episode) { mutableStateOf<DownloadVideo?>(null) }
    val downloadLookup = remember(episode) { Mutex() }
    var downloadOptions by remember(episode) { mutableStateOf<List<DownloadVideo>?>(null) }
    var nextDownload by remember(episode) { mutableIntStateOf(0) }
    var hlsQualities by remember(episode) { mutableStateOf<List<Int>>(emptyList()) }
    var hdHeight by remember(episode) { mutableStateOf<Int?>(null) }
    var returnToStream by remember(episode) { mutableStateOf<Source?>(null) }
    var ready by remember(episode) { mutableStateOf(false) }
    var preparing by remember(episode) { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var playRequested by remember(episode) { mutableStateOf(false) }
    var ended by remember(episode) { mutableStateOf(false) }
    var quality by remember(episode) { mutableStateOf("") }
    var tracks by remember(episode) { mutableStateOf<List<TextTrack>>(emptyList()) }
    var captionName by remember { mutableStateOf<String?>(null) }
    var captionPreference by remember { mutableStateOf(activity.subtitlePreference) }
    var elapsedMs by remember(episode) { mutableLongStateOf(0L) }
    var durationMs by remember(episode) { mutableLongStateOf(0L) }
    var scrubMs by remember(episode) { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var checkingQualities by remember(episode) { mutableStateOf(false) }
    var controls by remember(episode) { mutableStateOf(true) }
    var controlsHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val subtitleGapPx = with(density) { 20.dp.roundToPx() }
    var interaction by remember { mutableIntStateOf(0) }
    var selected by remember(episode) { mutableStateOf("player/timeline") }
    var move by remember { mutableStateOf(FocusMove("player/timeline", 0)) }
    val accountRevision = remember(title.id) { activity.media.accountRevision }
    var position by remember(episode) {
        mutableLongStateOf(activity.media.lastPlayed(title.id)?.takeIf { it.episode == episode.key }?.timeMs ?: 0L)
    }
    var lastSaved by remember(episode) { mutableLongStateOf(-1L) }
    var playAfterSwitch by remember(episode) { mutableStateOf(true) }
    val hiddenFocus = remember { FocusRequester() }
    val nextEpisode = remember(catalog, episode) { if (title.series) catalog.nextEpisode(episode) else null }

    fun saveProgress() {
        if (!ready) return
        val time = player.currentPosition
        val durationMs = player.duration
        if (time !in 1_000L..durationMs || time / 1000 == lastSaved) return
        lastSaved = time / 1000
        val report = source == Source.HLS || source == Source.HD
        activity.lifecycleScope.launch {
            activity.media.recordProgress(title.id, streamEpisode, time, durationMs, accountRevision, report)
        }
    }

    BackHandler {
        if (scrubMs != null) {
            scrubMs = null
            interaction++
        } else {
            saveProgress()
            onClose()
        }
    }

    fun rememberPlayback() {
        if (ready) {
            position = player.currentPosition
            playAfterSwitch = player.playWhenReady
        }
    }

    fun nextAfterFailure() {
        rememberPlayback()
        if (source == Source.DOWNLOAD) candidate = null
        preparing = false
        ready = false
        source = when (source) {
            Source.HLS, Source.DOWNLOAD -> Source.FIND_DOWNLOAD
            else -> Source.UNAVAILABLE
        }
    }

    suspend fun findDownload(): DownloadVideo? = downloadLookup.withLock {
        candidate?.let { return@withLock it }

        suspend fun rankedDownloads(): List<DownloadVideo> {
            while (true) {
                var rejected = false
                val releases = activity.media.alternatives(title, episode)
                val available = coroutineScope {
                    releases.chunked(4).flatMap { batch ->
                        batch.map { release ->
                            async {
                                try {
                                    seekableDownloadSize(release.url)?.let { release to it }
                                } catch (_: ExpiredMediaLink) {
                                    rejected = true
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
                if (available.isNotEmpty() || !rejected) return available.sortedByDescending { it.second }.map { it.first }
                if (renewDownloads) throw ExpiredMediaLink()
                renewDownloads = true
                activity.media.clearPlaybackReads()
            }
        }

        val options = downloadOptions ?: tryNama { rankedDownloads() }.getOrNull()?.also { downloadOptions = it }.orEmpty()
        options.getOrNull(nextDownload)?.also {
            nextDownload++
            candidate = it
        }
    }

    fun selectCaption(track: TextTrack?) {
        val params = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
        if (track != null) params.setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
        val updated = params.build()
        if (updated != player.trackSelectionParameters) player.trackSelectionParameters = updated
    }

    fun preferredCaption() = tracks.firstOrNull { it.preference == captionPreference && it.name == captionName }
        ?: tracks.firstOrNull { it.preference == captionPreference }

    fun playTrack(track: TrackSelectionOverride) {
        ready = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(track).build()
        if (player.duration > 0 && position >= player.duration) player.seekTo(0)
        if (activity.foreground && playAfterSwitch) player.play()
    }

    val saveLatestProgress by rememberUpdatedState(::saveProgress)
    DisposableEffect(player) {
        onDispose {
            saveLatestProgress()
            player.release()
        }
    }
    DisposableEffect(player, episode) {
        val listener = object : Player.Listener {
            override fun onEvents(playerInstance: Player, events: Player.Events) {
                if (!preparing) return
                if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                    val http = generateSequence<Throwable>(player.playerError) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val expired = http != null && isExpiredMediaStatus(http.responseCode)
                    if (expired && renewedSource == null && (source == Source.HLS || source == Source.HD)) {
                        rememberPlayback()
                        renewedSource = source
                        preparing = false
                        ready = false
                        source = Source.RENEW_STREAM
                        return
                    }
                    if (expired && source == Source.DOWNLOAD && !renewDownloads) {
                        renewDownloads = true
                        activity.media.clearPlaybackReads()
                        downloadOptions = null
                        nextDownload = 0
                    }
                    nextAfterFailure()
                    return
                }
                running = player.isPlaying
                ended = player.playbackState == Player.STATE_ENDED
                playRequested = player.playWhenReady && !ended
                val size = player.videoSize
                if (size.height > 0) {
                    quality = if (isUhd(size.width, size.height)) "4K · ${size.width} × ${size.height}" else "${size.height}p"
                }
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                    tracks = player.currentTracks.textTracks()
                    selectCaption(preferredCaption())
                    if (source == Source.HLS || source == Source.HD) {
                        player.currentTracks.videoQualities().takeIf { it.isNotEmpty() }?.let { hlsQualities = it }
                    }
                }
                if (ready || player.playbackState != Player.STATE_READY) return
                if (source == Source.DOWNLOAD && !player.isCurrentMediaItemSeekable) {
                    nextAfterFailure()
                    return
                }
                val requested = player.currentTracks.videoOverride(source, hdHeight)
                if (requested == null) {
                    retainedHls = source == Source.HLS && hlsQualities.any { it < 2160 }
                    nextAfterFailure()
                    return
                }
                playTrack(requested)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(source, episode) {
        preparing = false
        ready = false
        val keepHls = retainedHls && (source == Source.FIND_DOWNLOAD || source == Source.HD) &&
            player.playbackState == Player.STATE_READY && player.playerError == null
        if (source != Source.FIND_DOWNLOAD) retainedHls = false
        if (keepHls) player.pause() else {
            player.stop()
            player.clearMediaItems()
            quality = ""
            tracks = emptyList()
        }
        running = false
        playRequested = false
        ended = false
        controls = true
        when (source) {
            Source.FIND_DOWNLOAD -> {
                val available = findDownload()
                val previous = returnToStream
                source = when {
                    available != null -> Source.DOWNLOAD
                    previous != null -> previous.also { returnToStream = null }
                    hlsQualities.none { it < 2160 } -> Source.UNAVAILABLE
                    else -> Source.HD
                }
            }
            Source.RENEW_STREAM -> {
                val fresh = tryNama { activity.media.renewEpisode(title, episode.key) }.getOrNull()
                if (fresh != null) {
                    streamEpisode = fresh
                    source = renewedSource ?: Source.HLS
                } else source = if (renewedSource == Source.HLS) Source.FIND_DOWNLOAD else Source.UNAVAILABLE
            }
            Source.HLS, Source.HD, Source.DOWNLOAD -> {
                val hls = source != Source.DOWNLOAD
                val url = if (hls) streamEpisode.hls else candidate?.url
                if (url == null) {
                    source = Source.UNAVAILABLE
                    return@LaunchedEffect
                }
                val hdTrack = if (keepHls) player.currentTracks.videoOverride(Source.HD, hdHeight) else null
                if (hdTrack != null) {
                    preparing = true
                    playTrack(hdTrack)
                } else {
                    val captions = if (hls) streamEpisode.captions else candidate?.captions.orEmpty()
                    val item = MediaItem.Builder().setUri(url)
                        .setSubtitleConfigurations(captions.distinctBy { it.language }.map(Caption::subtitle))
                    if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    player.playWhenReady = false
                    preparing = true
                    player.setMediaItem(item.build(), position)
                    player.prepare()
                }
                delay(20_000)
                if (preparing && (!ready || quality.isBlank() || (source != Source.HD && !quality.startsWith("4K")))) {
                    nextAfterFailure()
                }
            }
            Source.UNAVAILABLE -> Unit
        }
    }
    LaunchedEffect(episode, source) {
        while (true) {
            elapsedMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    LaunchedEffect(episode, dialog) {
        if (dialog == "quality") {
            checkingQualities = true
            try {
                findDownload()
            } finally {
                checkingQualities = false
            }
        }
    }
    LaunchedEffect(activity.foreground) {
        if (!activity.foreground) {
            saveProgress()
            player.pause()
        }
    }
    LaunchedEffect(episode, running, ended) {
        if (ended) {
            saveProgress()
            controls = true
        }
        while (running) {
            delay(30_000)
            saveProgress()
        }
    }

    val canSeek = ready && player.isCurrentMediaItemSeekable && durationMs > 0
    val chapterEnd = if (canSeek && !ended && source != Source.DOWNLOAD) {
        streamEpisode.markers.intro?.takeIf { elapsedMs in it && it.last < durationMs }?.let { it.last + 1 }
    } else null
    val credits = canSeek && !ended && source != Source.DOWNLOAD &&
        streamEpisode.markers.creditsMs?.let { elapsedMs in it..<durationMs } == true
    val chapterLabel = when {
        chapterEnd != null -> "Skip intro"
        credits && nextEpisode != null -> "Next episode"
        credits -> "Skip credits"
        else -> null
    }
    val chapterId = if (credits && nextEpisode != null) "player/next" else "player/chapter"
    LaunchedEffect(running, controls, dialog, interaction, scrubMs) {
        if (running && controls && dialog == null && scrubMs == null) {
            delay(6_000)
            controls = false
        }
    }
    LaunchedEffect(controls, ready, dialog, episode, chapterLabel, ended) {
        if (!ready || dialog != null) return@LaunchedEffect
        val focus = when {
            !controls -> chapterId.takeIf { chapterLabel != null }
            ended -> if (nextEpisode != null) "player/next" else "player/play"
            selected == "player/chapter" && chapterLabel == null -> "player/timeline"
            else -> selected
        }
        if (focus == null) {
            withFrameNanos { }
            hiddenFocus.requestFocus()
        } else {
            move = FocusMove(focus, move.revision + 1)
        }
    }

    fun seekTo(target: Long) {
        if (canSeek) {
            elapsedMs = target.coerceIn(0, durationMs)
            player.seekTo(elapsedMs)
        }
    }

    fun setPlaying(play: Boolean) {
        if (play) {
            if (ended) seekTo(0)
            player.play()
        } else {
            saveProgress()
            player.pause()
        }
    }

    fun switchEpisode(target: MediaEpisode) {
        dialog = null
        if (target != episode) {
            saveProgress()
            onEpisode(target)
        }
    }

    fun switchQuality(choice: VideoQuality) {
        val height = choice.height.takeIf { choice.source == Source.HD }
        if (source == choice.source) {
            if (choice.source == Source.HD && height != hdHeight) {
                player.currentTracks.videoOverride(Source.HD, height)?.let {
                    hdHeight = height
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(it).build()
                }
            }
        } else {
            saveProgress()
            rememberPlayback()
            if (choice.source == Source.DOWNLOAD) returnToStream = source
            else {
                returnToStream = null
                hdHeight = height
            }
            source = choice.source
        }
        dialog = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { key ->
        val event = key.nativeKeyEvent
        interaction++
        if (dialog != null || !ready) return@onPreviewKeyEvent false
        val down = event.action == KeyEvent.ACTION_DOWN
        val keyCode = event.keyCode
        when {
            keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE) -> {
                if (down && event.repeatCount == 0) setPlaying(
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) !player.playWhenReady else keyCode == KeyEvent.KEYCODE_MEDIA_PLAY,
                )
                true
            }
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekTo(elapsedMs + if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -SEEK_JUMP else SEEK_JUMP)
                true
            }
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                (!controls || selected == "player/timeline") -> {
                if (down) {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    val scrub = scrubMs
                    if (scrub == null) seekTo(elapsedMs + direction * SEEK_JUMP)
                    else scrubMs = (scrub + direction * SEEK_JUMP).coerceIn(0, durationMs)
                    if (!controls) {
                        selected = "player/timeline"
                        controls = true
                    }
                }
                true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                if (down && controls) {
                    scrubMs = null
                    controls = false
                }
                true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (scrubMs != null) true
                else if (!controls) {
                    if (down) {
                        selected = "player/play"
                        controls = true
                    }
                    true
                } else if (selected == "player/timeline") {
                    if (down) move = FocusMove("player/play", move.revision + 1)
                    true
                } else false
            }
            !controls && chapterLabel == null &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    setPlaying(!player.playWhenReady || ended)
                    selected = "player/play"
                    controls = true
                }
                true
            }
            else -> false
        }
    }) {
        val visibleVideo = ready && quality.isNotBlank() &&
            (source == Source.HD || quality.startsWith("4K"))
        AndroidView(
            factory = { context ->
                PlayerView(context).also {
                    it.useController = false
                    it.isFocusable = false
                    it.isFocusableInTouchMode = false
                    it.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    it.player = player
                    it.subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setStyle(CaptionStyleCompat(
                            android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK,
                            Typeface.create("sans-serif", Typeface.NORMAL),
                        ))
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val overlayHeight = if (controls && ready) controlsHeightPx else 0
                view.subtitleView?.apply {
                    doOnLayout {
                        val (inset, extra) = subtitleBottomInsets(view.height, height, overlayHeight, subtitleGapPx)
                        setPadding(0, 0, 0, inset)
                        setBottomPaddingFraction(extra)
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, height * SUBTITLE_TEXT_FRACTION)
                    }
                }
            },
            onRelease = { it.player = null },
        )
        if (!visibleVideo) Box(Modifier.fillMaxSize().background(Color.Black))
        if (!controls && chapterLabel == null) Box(Modifier.fillMaxSize().focusRequester(hiddenFocus).focusable())
        if (!visibleVideo && source != Source.UNAVAILABLE) {
            Text("Preparing playback…", Modifier.align(Alignment.Center), fontSize = 21.sp)
        }
        if (controls && ready) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { controlsHeightPx = it.height }
                    .background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xE9000000)),
                )).padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val heading = listOfNotNull(title.name, if (title.series) "S${episode.season} · E${episode.number}" else null)
                Text(heading.joinToString("  "), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    if (nextEpisode != null && !(credits && chapterLabel == "Next episode")) {
                        MediaIconButton(
                            "Next episode", R.drawable.media_next_episode, "player/next", move, { selected = it },
                            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
                        ) { switchEpisode(nextEpisode) }
                    }
                    MediaIconButton(
                        if (playRequested) "Pause" else if (ended) "Replay" else "Play",
                        if (playRequested) R.drawable.media_pause else R.drawable.media_play,
                        "player/play", move, { selected = it },
                        modifier = Modifier.align(Alignment.Center).size(56.dp), shape = CircleShape,
                    ) { setPlaying(!player.playWhenReady || ended) }
                    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaIconButton("Subtitles", R.drawable.media_subtitles, "player/subtitles", move, { selected = it }) {
                            dialog = "subtitles"
                        }
                        if (title.series) MediaIconButton("Episodes", R.drawable.media_episodes, "player/episodes", move,
                            { selected = it }) { dialog = "episodes" }
                        Action(quality.ifBlank { "Quality" }, {
                            checkingQualities = true
                            dialog = "quality"
                        }, "player/quality", move, { selected = it },
                            modifier = Modifier.semantics { contentDescription = "Quality" }, icon = R.drawable.media_quality)
                    }
                }
                PlaybackTimeline(
                    scrubMs ?: elapsedMs, durationMs, scrubMs != null, selected == "player/timeline", move, { selected = it },
                ) {
                    if (canSeek) {
                        val scrub = scrubMs
                        if (scrub == null) scrubMs = elapsedMs else {
                            seekTo(scrub)
                            scrubMs = null
                        }
                    }
                }
            }
        }
        if (chapterLabel != null) Action(
            chapterLabel,
            {
                when {
                    chapterEnd != null -> seekTo(chapterEnd)
                    nextEpisode != null -> switchEpisode(nextEpisode)
                    else -> seekTo(durationMs)
                }
            },
            chapterId, move, { selected = it },
            Modifier.align(Alignment.BottomEnd).padding(
                end = 40.dp, bottom = if (controls) with(density) { controlsHeightPx.toDp() } + 12.dp else 26.dp,
            ),
            icon = R.drawable.media_next_episode,
        )
    }
    when {
        source == Source.UNAVAILABLE -> MediaDialog(
            "Can’t play this title", "30nama didn’t provide a compatible source.",
            listOf("Back"), close = onClose,
        ) { onClose() }
        dialog == "subtitles" -> MediaDialog("Subtitles", "No subtitles are available for this source.".takeIf { tracks.isEmpty() },
            listOf("Off") + tracks.map(TextTrack::name),
            initial = tracks.indexOf(preferredCaption()) + 1, close = { dialog = null }) {
            val track = tracks.getOrNull(it - 1)
            captionName = track?.name
            captionPreference = track?.preference
            activity.subtitlePreference = captionPreference
            selectCaption(track)
            dialog = null
            interaction++
        }
        dialog == "episodes" -> EpisodePicker(
            catalog.episodes, episode, activity.media.lastPlayed(title.id), { dialog = null }, ::switchEpisode,
        )
        dialog == "quality" && checkingQualities -> MediaDialog(
            "Quality", "Checking available qualities…", listOf("Back"), close = { dialog = null },
        ) { dialog = null }
        dialog == "quality" -> {
            val choices = buildList {
                if (2160 in hlsQualities) add(VideoQuality(Source.HLS, 2160))
                if (candidate != null) add(VideoQuality(Source.DOWNLOAD, 2160))
                hlsQualities.filter { it < 2160 }.forEach { add(VideoQuality(Source.HD, it)) }
            }
            val currentHeight = if (source == Source.HD) hdHeight ?: player.videoSize.height else 2160
            MediaDialog(
                "Quality", "Playback can continue at its current quality.".takeIf { choices.isEmpty() },
                choices.map(VideoQuality::label).ifEmpty { listOf("Back") },
                initial = choices.indexOf(VideoQuality(source, currentHeight)).coerceAtLeast(0),
                close = { dialog = null },
            ) { index -> choices.getOrNull(index)?.let(::switchQuality) ?: run { dialog = null } }
        }
    }
}

@Composable
private fun PlaybackTimeline(
    positionMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    focused: Boolean,
    move: FocusMove?,
    onFocused: (String) -> Unit,
    click: () -> Unit,
) {
    val fraction = if (durationMs > 0) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val time = clockTime(positionMs / 1000)
    val total = clockTime(durationMs / 1000)
    FocusSurface(
        "player/timeline", move, onFocused, click,
        Modifier.fillMaxWidth().height(48.dp).semantics {
            contentDescription = if (scrubbing) "Seek preview, $time of $total" else "Seek, $time of $total"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
        fill = Color.Transparent,
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(total, color = secondary, fontSize = 14.sp)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                val rail = RoundedCornerShape(3.dp)
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(if (focused) 5.dp else 4.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0x575F656D), Color(0x62797E86))), rail)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(
                        Brush.horizontalGradient(listOf(Color(0xFF9CA4AF), Color.White)), rail,
                    ))
                }
                if (focused) {
                    val thumb = if (scrubbing) 12.dp else 10.dp
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - thumb).coerceAtLeast(0.dp) * fraction)
                        .size(thumb).background(Color.White, CircleShape))
                }
            }
        }
    }
}

@Composable
internal fun MediaIconButton(
    label: String,
    icon: Int,
    id: String,
    move: FocusMove?,
    focused: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape? = null,
    saved: Boolean? = null,
    click: () -> Unit,
) {
    FocusSurface(
        id, move, focused, click, modifier.semantics {
            contentDescription = label
            saved?.let { selected = it }
        }, enabled = enabled, glassShape = shape,
        fill = Color(0xFF453727).takeIf { saved == true },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
            if (saved == true) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(15.dp)
                        .background(Color(0xFFFFDA99), CircleShape).border(1.dp, ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.media_check), null, Modifier.size(11.dp),
                        colorFilter = ColorFilter.tint(ink))
                }
            }
        }
    }
}

internal fun clockTime(seconds: Long): String = if (seconds >= 3600) {
    String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
} else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
