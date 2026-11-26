package tv.scenichome.launcher

import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

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
    var primaryCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var secondaryCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    val captionSelector = remember { CaptionTrackSelector(activity) }
    val persianTypeface = remember { activity.resources.getFont(R.font.shabnam_regular) }
    val renderers = remember {
        CaptionRenderers(activity, object : TextOutput {
            override fun onCues(cueGroup: CueGroup) { secondaryCues = cueGroup.cues }
        })
    }
    val player = remember {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val extractors = HevcBaseLayerExtractors({ renderers.route.hevcBase })
        ExoPlayer.Builder(activity, renderers).setTrackSelector(captionSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(activity, extractors)).build().also { exo ->
                if (BuildConfig.DEBUG) exo.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                        android.util.Log.i("BanamaDecoder", "Video size: ${videoSize.width}x${videoSize.height}")
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        android.util.Log.i("BanamaDecoder", "Video decoder: $decoderName")
                    }
                })
            }
    }
    var streamEpisode by remember(episode) { mutableStateOf(episode) }
    val downloadLookup = remember(episode) { Mutex() }
    var downloadOptions by remember(episode) { mutableStateOf<List<DownloadVideo>?>(null) }
    var downloadLookupFailed by remember(episode) { mutableStateOf(false) }
    var hlsQualities by remember(episode) { mutableStateOf<List<VideoQuality>>(emptyList()) }
    var activeQuality by remember(episode) { mutableStateOf<VideoQuality?>(null) }
    var request by remember(episode) { mutableStateOf<Pair<VideoQuality, Int>?>(null) }
    var ready by remember(episode) { mutableStateOf(false) }
    var unavailable by remember(episode) { mutableStateOf(false) }
    var qualityProblem by remember(episode) { mutableStateOf<String?>(null) }
    var playbackEvent by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var playRequested by remember(episode) { mutableStateOf(false) }
    var ended by remember(episode) { mutableStateOf(false) }
    var tracks by remember(episode) { mutableStateOf<List<TextTrack>>(emptyList()) }
    var captionName by remember { mutableStateOf<String?>(null) }
    var captionPreference by remember { mutableStateOf(activity.subtitlePreference) }
    var selectedCaptions by remember(episode) { mutableStateOf<List<TextTrack>>(emptyList()) }
    var elapsedMs by remember(episode) { mutableLongStateOf(0L) }
    var durationMs by remember(episode) { mutableLongStateOf(0L) }
    var scrubMs by remember(episode) { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
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
        val report = activeQuality?.download == null
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

    fun selectStream(choice: VideoQuality) {
        val override = choice.streamTrack ?: return
        val selected = player.currentTracks.groups.any { group ->
            group.mediaTrackGroup == override.mediaTrackGroup && group.isTrackSelected(override.trackIndices.single())
        }
        // Some codecs keep the previous output buffer size across an in-place rendition change.
        if (!selected) player.stop()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, choice.resolution ?: Int.MAX_VALUE).setOverrideForType(override).build()
        if (!selected) player.prepare()
    }

    fun preferredCaptions(): List<TextTrack> {
        if (captionPreference == BILINGUAL_SUBTITLES) {
            return tracks.bilingualTracks().ifEmpty {
                listOfNotNull(tracks.firstOrNull { it.preference == "fa" } ?: tracks.firstOrNull { it.preference == "en" })
            }
        }
        val track = tracks.firstOrNull { it.preference == captionPreference && it.name == captionName }
            ?: tracks.firstOrNull { it.preference == captionPreference }
        return listOfNotNull(track)
    }

    fun selectCaptions() {
        val next = preferredCaptions()
        if (next.map(TextTrack::override) == selectedCaptions.map(TextTrack::override)) return
        if (next.firstOrNull()?.override != selectedCaptions.firstOrNull()?.override) primaryCues = emptyList()
        if (next.getOrNull(1)?.override != selectedCaptions.getOrNull(1)?.override) secondaryCues = emptyList()
        selectedCaptions = next
        captionSelector.selectCaptions(next)
    }

    suspend fun downloads(refresh: Boolean = false): List<DownloadVideo> = downloadLookup.withLock {
        if (refresh) {
            activity.media.clearPlaybackReads()
            downloadOptions = null
        }
        downloadOptions?.let { return@withLock it }
        val options = tryNama { activity.media.alternatives(title, episode) }
        downloadLookupFailed = options.isFailure
        options.getOrNull()?.also { downloadOptions = it }.orEmpty()
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
            override fun onCues(cueGroup: CueGroup) { primaryCues = cueGroup.cues }

            override fun onEvents(playerInstance: Player, events: Player.Events) {
                playbackEvent++
                running = player.isPlaying
                ended = player.playbackState == Player.STATE_ENDED
                playRequested = player.playWhenReady && !ended
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                    tracks = player.currentTracks.textTracks()
                    selectCaptions()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var preparedUrl by remember(episode) { mutableStateOf<String?>(null) }
    LaunchedEffect(episode, request) {
        val previous = activeQuality
        ready = false
        unavailable = false
        qualityProblem = null
        controls = true
        player.pause()
        val failedDownloads = mutableSetOf<String>()
        val failedVariants = mutableSetOf<VideoQuality>()
        var renewedStream = false
        var renewedDownloads = false
        var streamUnavailable = false
        var attemptProblem: String? = null

        fun expiredLink(): Boolean = generateSequence<Throwable>(player.playerError) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().any { isExpiredMediaStatus(it.responseCode) }

        fun missingVideo(): Format? {
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            return groups.firstOrNull()?.takeIf { groups.none { it.isSelected } }?.getTrackFormat(0)
        }

        fun nextDecoder(): DecoderRoute? {
            val error = player.playerError
            val format = if (error == null) missingVideo() else {
                if (!isDecoderError(error.errorCode)) return null
                error.rendererFormat
            } ?: return null
            if (format.cryptoType != C.CRYPTO_TYPE_NONE) return null
            if (BuildConfig.DEBUG) android.util.Log.i("BanamaDecoder",
                "Retry check: ${error?.errorCodeName ?: "unselected track"} ${format.sampleMimeType} ${format.codecs}")
            return renderers.route.retry(format.sampleMimeType, format.codecs)
        }

        suspend fun prepareOnce(url: String, captions: List<Caption>, stream: Boolean, route: DecoderRoute): Boolean {
            if (preparedUrl == url && renderers.route == route && player.playerError == null &&
                player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)) return true
            preparedUrl = null
            player.stop()
            player.clearMediaItems()
            renderers.route = route
            tracks = emptyList()
            primaryCues = emptyList()
            secondaryCues = emptyList()
            selectCaptions()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides()
                .setMaxVideoSize(Int.MAX_VALUE, if (stream) activity.preferredResolution else Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(stream).build()
            val item = MediaItem.Builder().setUri(url)
                .setSubtitleConfigurations(captions.distinctBy { subtitlePreferenceKey(it.language, "") }.map(Caption::subtitle))
            if (stream) item.setMimeType(MimeTypes.APPLICATION_M3U8)
            player.setMediaItem(item.build(), position)
            player.prepare()
            val available = withTimeoutOrNull(20_000) {
                snapshotFlow {
                    playbackEvent.let {
                        player.playerError != null || player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)
                    }
                }.first { it }
                player.playerError == null
            } == true
            if (available && missingVideo() == null) {
                preparedUrl = url
                return true
            }
            return false
        }

        suspend fun prepare(url: String, captions: List<Caption>, stream: Boolean, initial: DecoderRoute): Boolean {
            var route = initial
            while (!prepareOnce(url, captions, stream, route)) route = nextDecoder() ?: return false
            return true
        }

        suspend fun renewStream(): Boolean {
            if (renewedStream) return false
            renewedStream = true
            val fresh = tryNama { activity.media.renewEpisode(title, episode.key) }.getOrNull() ?: return false
            streamEpisode = fresh
            return true
        }

        suspend fun openStream(route: DecoderRoute = DecoderRoute()): List<VideoQuality> {
            if (streamUnavailable) return emptyList()
            while (true) {
                val url = streamEpisode.hls ?: break
                if (prepare(url, streamEpisode.captions, stream = true, route)) {
                    hlsQualities = player.videoSize.let { player.currentTracks.videoQualities(it.width, it.height) }
                    return hlsQualities.filter { variant -> failedVariants.none { variant.sameVariant(it) } }
                }
                if (!expiredLink() || !renewStream()) break
            }
            streamUnavailable = true
            hlsQualities = emptyList()
            return emptyList()
        }

        suspend fun freshDownload(release: DownloadVideo): DownloadVideo? {
            if (renewedDownloads) return null
            renewedDownloads = true
            return downloads(refresh = true).firstOrNull {
                (release.id != 0 && it.id == release.id) || httpsUri(it.url)?.path == httpsUri(release.url)?.path
            }
        }

        suspend fun openDownload(chosen: DownloadVideo, route: DecoderRoute = DecoderRoute()): VideoQuality? {
            attemptProblem = null
            var release = chosen
            while (failedDownloads.add(release.url)) {
                val range = try {
                    if (preparedUrl == release.url && player.playerError == null) DownloadRange.SEEKABLE
                    else downloadRange(release.url)
                } catch (_: ExpiredMediaLink) {
                    attemptProblem = "The download link expired or the server denied access."
                    release = freshDownload(release) ?: return null
                    continue
                }
                if (range != DownloadRange.SEEKABLE) {
                    attemptProblem = if (range == DownloadRange.INVALID) "The server didn’t return valid byte ranges for seeking."
                        else "The player couldn’t read the release from the server."
                    return null
                }
                if (prepare(release.url, release.captions, stream = false, route)) {
                    if (!player.isCurrentMediaItemSeekable) {
                        attemptProblem = "The file opened, but the player couldn’t seek through it."
                        return null
                    }
                    val video = player.videoSize.let { player.currentTracks.videoQualities(it.width, it.height) }
                        .firstOrNull()
                    if (video == null) {
                        attemptProblem = "This device couldn’t decode this release."
                        return null
                    }
                    return release.videoQuality().copy(resolution = release.resolution ?: video.resolution,
                        encoder = release.encoder.ifBlank { video.encoder })
                }
                if (!expiredLink()) {
                    attemptProblem = if (missingVideo() != null) "This device couldn’t decode this release."
                        else releasePlaybackProblem(player.playerError?.errorCode)
                    return null
                }
                attemptProblem = "The download link expired or the server denied access."
                release = freshDownload(release) ?: return null
            }
            return null
        }

        suspend fun openChoice(choice: VideoQuality): VideoQuality? {
            val download = choice.download
            return if (download != null) openDownload(download)
            else openStream().firstOrNull { it.sameVariant(choice) }
        }

        suspend fun bestQuality(): VideoQuality? {
            val target = activity.preferredResolution
            val streams = openStream()
            streams.firstOrNull { it.resolution == target }?.let { return it }
            val releases = downloads()
            for (resolution in rankResolutions(streams.map { it.resolution } + releases.map { it.resolution }, target)) {
                val stream = streams.firstOrNull { it.resolution == resolution }
                if (stream != null) {
                    openChoice(stream)?.let { return it }
                }
                for (release in releases.filter { it.resolution == resolution }) {
                    openDownload(release)?.let { return it }
                }
            }
            return null
        }

        val requested = request?.first
        var choice = requested?.let { openChoice(it) }
        var usingRequested = requested != null && choice != null
        if (requested != null && choice == null) {
            qualityProblem = attemptProblem ?: "The requested quality couldn’t be opened."
        }
        choice = choice ?: previous?.takeIf { requested != null }?.let { openChoice(it) } ?: bestQuality()

        while (choice != null) {
            activeQuality = choice
            selectStream(choice)
            if (player.duration in 1..position) player.seekTo(0)
            ready = true
            if (activity.foreground && playAfterSwitch) player.play()
            if (qualityProblem != null && dialog == null) dialog = "quality_unavailable"
            val error = snapshotFlow { playbackEvent.let { player.playerError } }.filterNotNull().first()

            rememberPlayback()
            ready = false
            val failed = activeQuality ?: choice
            val release = failed.download
            attemptProblem = if (expiredLink()) "The download link expired or the server denied access." else null
            val retry = nextDecoder()
            choice = if (release != null) {
                when {
                    expiredLink() -> freshDownload(release)?.let { openDownload(it, renderers.route) }
                    retry != null -> {
                        failedDownloads.remove(release.url)
                        openDownload(release, retry)
                    }
                    else -> null
                }
            } else if (retry != null) {
                openStream(retry).firstOrNull { it.sameVariant(failed) }
            } else if (expiredLink() && renewStream()) {
                openChoice(failed)
            } else null
            if (choice == null && release == null) failedVariants.add(failed)
            if (choice == null && usingRequested) {
                qualityProblem = attemptProblem ?: releasePlaybackProblem(error.errorCode)
                usingRequested = false
            }
            choice = choice ?: bestQuality()
        }

        player.stop()
        activeQuality = null
        unavailable = true
    }
    LaunchedEffect(episode, activeQuality) {
        while (true) {
            elapsedMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    LaunchedEffect(episode, dialog) {
        if (dialog == "quality") {
            downloadLookupFailed = false
            downloads()
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
    val chapterEnd = if (canSeek && !ended && activeQuality?.download == null) {
        streamEpisode.markers.intro?.takeIf { elapsedMs in it && it.last < durationMs }?.let { it.last + 1 }
    } else null
    val credits = canSeek && !ended && activeQuality?.download == null &&
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
        choice.resolution?.takeIf { it in savedResolutions }?.let { activity.preferredResolution = it }
        if (choice != activeQuality) {
            saveProgress()
            rememberPlayback()
            if (activeQuality?.streamTrack != null && choice.streamTrack != null) {
                activeQuality = choice
                selectStream(choice)
            } else {
                request = choice to ((request?.second ?: 0) + 1)
            }
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
        AndroidView(
            factory = { context ->
                PlayerView(context).also {
                    it.useController = false
                    it.isFocusable = false
                    it.isFocusableInTouchMode = false
                    it.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    it.player = player
                    it.subtitleView?.let { original ->
                        original.visibility = View.GONE
                        val captions = SubtitleView(context).apply {
                            id = R.id.banama_subtitle_overlay
                            setStyle(CaptionStyleCompat(
                                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK,
                                Typeface.create("sans-serif", Typeface.NORMAL),
                            ))
                        }
                        (original.parent as ViewGroup).addView(captions, original.layoutParams)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.keepScreenOn = activity.foreground && ready && running
                val overlayHeight = if (controls && ready) controlsHeightPx else 0
                view.findViewById<SubtitleView>(R.id.banama_subtitle_overlay)?.apply {
                    val main = primaryCues.takeIf { selectedCaptions.isNotEmpty() }.orEmpty()
                    val extra = secondaryCues.takeIf { selectedCaptions.size == 2 }.orEmpty()
                    setCues(displayCaptions(main, extra, persianTypeface, selectedCaptions.firstOrNull()?.preference == "fa"))
                    doOnLayout {
                        val (inset, extra) = subtitleBottomInsets(view.height, height, overlayHeight, subtitleGapPx)
                        setPadding(0, 0, 0, inset)
                        setBottomPaddingFraction(extra)
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, height * SUBTITLE_TEXT_FRACTION)
                    }
                }
            },
            onRelease = {
                it.keepScreenOn = false
                it.player = null
            },
        )
        if (!ready) Box(Modifier.fillMaxSize().background(Color.Black))
        if (!controls && chapterLabel == null) Box(Modifier.fillMaxSize().focusRequester(hiddenFocus).focusable())
        if (!ready && !unavailable) {
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
                        val resolution = activeQuality?.resolution
                        Action(resolution?.resolutionLabel ?: "Quality", { dialog = "quality" }, "player/quality", move, { selected = it },
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
        unavailable -> MediaDialog(
            "Can’t play this title", qualityProblem ?: "30nama didn’t provide a compatible source.",
            listOf("Back"), close = onClose,
        ) { onClose() }
        dialog == "subtitles" -> {
            val bilingual = tracks.bilingualTracks().isNotEmpty()
            val bilingualIndex = if (bilingual) 1 else -1
            val trackOffset = if (bilingual) 2 else 1
            val selectIndex = if (bilingual && captionPreference == BILINGUAL_SUBTITLES) 1
                else tracks.indexOf(preferredCaptions().firstOrNull()).let { if (it >= 0) it + trackOffset else 0 }
            MediaDialog("Subtitles", "No subtitles are available for this source.".takeIf { tracks.isEmpty() },
                listOf("Off") + listOfNotNull("Persian + English".takeIf { bilingual }) + tracks.map(TextTrack::name),
                initial = selectIndex, close = { dialog = null }) { index ->
                val track = tracks.getOrNull(index - trackOffset)
                captionName = track?.name
                captionPreference = if (index == bilingualIndex) BILINGUAL_SUBTITLES else track?.preference
                activity.subtitlePreference = captionPreference
                selectCaptions()
                dialog = null
                interaction++
            }
        }
        dialog == "episodes" -> EpisodePicker(
            catalog.episodes, episode, activity.media.lastPlayed(title.id), { dialog = null }, ::switchEpisode,
        )
        dialog == "quality_unavailable" -> MediaDialog(
            "Release unavailable", "$qualityProblem Playing another quality.", listOf("Close"), close = {
                qualityProblem = null
                dialog = null
            },
        ) {
            qualityProblem = null
            dialog = null
        }
        dialog == "quality" -> {
            val releases = downloadOptions
            val current = activeQuality
            val choices = hlsQualities + releases.orEmpty().map { release ->
                current?.takeIf { it.download == release } ?: release.videoQuality()
            }
            if ((releases != null || downloadLookupFailed) && choices.isNotEmpty()) {
                QualityPicker(choices, current, downloadLookupFailed, { dialog = null }, ::switchQuality)
            } else {
                val message = when {
                    downloadLookupFailed -> "File releases could not be loaded."
                    releases == null -> "Checking available qualities…"
                    else -> "No alternate qualities are available."
                }
                MediaDialog("Playback quality", message, listOf("Back"), close = { dialog = null }) { dialog = null }
            }
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
