package tv.scenichome.launcher

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

private enum class Source { HLS, FIND_DOWNLOAD, DOWNLOAD, ASK_HD, HD, UNAVAILABLE }

private data class TextTrack(val group: Tracks.Group, val index: Int, val name: String)

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

private fun Tracks.textTracks(): List<TextTrack> = groups.flatMap { group ->
    if (group.type != C.TRACK_TYPE_TEXT) return@flatMap emptyList()
    (0 until group.length).filter(group::isTrackSupported).map { index ->
        val format = group.getTrackFormat(index)
        val name = format.label ?: format.language?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        } ?: "Subtitle ${index + 1}"
        TextTrack(group, index, name)
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
    BackHandler(onBack = onClose)
    val player = remember {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        ExoPlayer.Builder(activity).build()
    }
    var source by remember(episode) { mutableStateOf(if (episode.hls == null) Source.FIND_DOWNLOAD else Source.HLS) }
    var candidate by remember(episode) { mutableStateOf<DownloadVideo?>(null) }
    val testedDownloads = remember(episode) { mutableSetOf<String>() }
    var lowerHls by remember(episode) { mutableStateOf(false) }
    var allowHd by remember { mutableStateOf(false) }
    var ready by remember(episode) { mutableStateOf(false) }
    var preparing by remember(episode) { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var ended by remember(episode) { mutableStateOf(false) }
    var quality by remember(episode) { mutableStateOf("") }
    var tracks by remember(episode) { mutableStateOf<List<TextTrack>>(emptyList()) }
    var captionName by remember { mutableStateOf<String?>(null) }
    var seconds by remember(episode) { mutableStateOf(0L) }
    var duration by remember(episode) { mutableStateOf(0L) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var controls by remember(episode) { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf("player/play") }
    var move by remember { mutableStateOf(FocusMove("player/play", 0)) }
    var position by remember(episode) {
        mutableStateOf(if (catalog.resumeFile > 0 && catalog.resumeFile == episode.fileId) catalog.resumeMs else 0L)
    }
    val hiddenFocus = remember { FocusRequester() }

    fun nextAfterFailure() {
        if (source == Source.HLS && ready) position = player.currentPosition
        preparing = false
        source = when (source) {
            Source.HLS, Source.DOWNLOAD -> Source.FIND_DOWNLOAD
            else -> Source.UNAVAILABLE
        }
    }

    fun selectCaption(track: TextTrack?) {
        val params = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
        if (track != null) params.setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
        player.trackSelectionParameters = params.build()
    }

    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player, episode) {
        val listener = object : Player.Listener {
            override fun onEvents(playerInstance: Player, events: Player.Events) {
                if (!preparing) return
                if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                    nextAfterFailure()
                    return
                }
                running = player.isPlaying
                ended = player.playbackState == Player.STATE_ENDED
                val size = player.videoSize
                if (size.height > 0) {
                    quality = if (isUhd(size.width, size.height)) "4K · ${size.width} × ${size.height}" else "${size.height}p"
                }
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) tracks = player.currentTracks.textTracks()
                if (source == Source.HLS) {
                    lowerHls = lowerHls || player.currentTracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                            val format = group.getTrackFormat(index)
                            group.isTrackSupported(index) && format.width > 0 && format.height > 0 &&
                                !isUhd(format.width, format.height)
                        }
                    }
                }
                if (ready || player.playbackState != Player.STATE_READY) return
                if (source == Source.DOWNLOAD && !player.isCurrentMediaItemSeekable) {
                    nextAfterFailure()
                    return
                }
                val requested = player.currentTracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_VIDEO) return@firstNotNullOfOrNull null
                    val indices = (0 until group.length).filter { index ->
                        val format = group.getTrackFormat(index)
                        group.isTrackSupported(index) && format.width > 0 && format.height > 0 &&
                            (isUhd(format.width, format.height) == (source != Source.HD))
                    }
                    if (indices.isEmpty()) null else TrackSelectionOverride(group.mediaTrackGroup, indices)
                }
                if (requested == null) {
                    nextAfterFailure()
                    return
                }
                ready = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(requested).build()
                selectCaption(tracks.firstOrNull { it.name == captionName })
                if (activity.foreground) player.play()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(source, episode) {
        preparing = false
        ready = false
        player.stop()
        player.clearMediaItems()
        quality = ""
        tracks = emptyList()
        running = false
        ended = false
        controls = true
        when (source) {
            Source.FIND_DOWNLOAD -> {
                val options = tryNama { activity.media.alternatives(title, catalog, episode) }.getOrNull().orEmpty()
                candidate = options.firstOrNull { testedDownloads.add(it.url) && supportsByteSeeking(it.url) }
                source = when {
                    candidate != null -> Source.DOWNLOAD
                    !lowerHls -> Source.UNAVAILABLE
                    allowHd -> Source.HD
                    else -> Source.ASK_HD
                }
            }
            Source.HLS, Source.HD, Source.DOWNLOAD -> {
                val hls = source != Source.DOWNLOAD
                val url = if (hls) episode.hls else candidate?.url
                if (url == null) {
                    source = Source.UNAVAILABLE
                    return@LaunchedEffect
                }
                val captions = if (hls) episode.captions else candidate?.captions.orEmpty()
                val item = MediaItem.Builder().setUri(url)
                    .setSubtitleConfigurations(captions.distinctBy { it.language }.map(Caption::subtitle))
                if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                player.playWhenReady = false
                preparing = true
                player.setMediaItem(item.build(), if (hls) position else 0L)
                player.prepare()
                delay(20_000)
                if (preparing && (!ready || quality.isBlank() || (source != Source.HD && !quality.startsWith("4K")))) {
                    nextAfterFailure()
                }
            }
            Source.ASK_HD, Source.UNAVAILABLE -> Unit
        }
    }
    LaunchedEffect(episode, source) {
        while (true) {
            seconds = player.currentPosition / 1000
            duration = player.duration.coerceAtLeast(0) / 1000
            delay(500)
        }
    }
    LaunchedEffect(activity.foreground) { if (!activity.foreground) player.pause() }
    LaunchedEffect(running, controls, dialog, interaction) {
        if (running && controls && dialog == null) {
            delay(6_000)
            controls = false
        }
    }
    LaunchedEffect(controls, ready, dialog, episode) {
        if (controls && ready && dialog == null) {
            move = FocusMove(selected, move.revision + 1)
        } else if (!controls) {
            withFrameNanos { }
            hiddenFocus.requestFocus()
        }
    }

    fun seek(ms: Long) {
        if (ready && player.isCurrentMediaItemSeekable) {
            player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0)))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { key ->
        val event = key.nativeKeyEvent
        interaction++
        if (dialog != null || !ready) return@onPreviewKeyEvent false
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000L); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
                else -> false
            }
        } else if (!controls && event.action == KeyEvent.ACTION_UP && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            )) {
            controls = true
            true
        } else false
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
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )
        if (!visibleVideo) Box(Modifier.fillMaxSize().background(Color.Black))
        if (!controls) Box(Modifier.fillMaxSize().focusRequester(hiddenFocus).focusable())
        if (!visibleVideo && source !in listOf(Source.ASK_HD, Source.UNAVAILABLE)) {
            Text(if (source == Source.FIND_DOWNLOAD) "Checking 4K downloads…" else "Preparing playback…",
                Modifier.align(Alignment.Center), fontSize = 21.sp)
        }
        if (controls && ready) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xE9000000)),
                )).padding(horizontal = 40.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val heading = listOfNotNull(title.name, if (title.series) "S${episode.season} · E${episode.number}" else null)
                Text(heading.joinToString("  "), fontSize = 23.sp)
                Text(listOf(quality, "${clockTime(seconds)} / ${clockTime(duration)}").filter(String::isNotBlank).joinToString("   ·   "),
                    color = secondary, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("−10 s", { seek(-10_000L) }, "player/back", move, { selected = it })
                    Action(if (running) "Pause" else if (ended) "Replay" else "Play", {
                        if (player.isPlaying) player.pause() else {
                            if (ended) player.seekTo(0)
                            player.play()
                        }
                    }, "player/play", move, { selected = it })
                    Action("+30 s", { seek(30_000L) }, "player/forward", move, { selected = it })
                    Action("Subtitles", { dialog = "subtitles" }, "player/subtitles", move, { selected = it })
                    if (title.series) Action("Episodes", { dialog = "episodes" }, "player/episodes", move, { selected = it })
                }
            }
        }
    }
    when {
        source == Source.ASK_HD -> MediaDialog(
            "Play in HD?", "A compatible 4K source is unavailable for this title.",
            listOf("Play in HD", "Back"), close = onClose,
        ) {
            if (it == 0) {
                allowHd = true
                source = Source.HD
            } else onClose()
        }
        source == Source.UNAVAILABLE -> MediaDialog(
            "Can’t play this title", "30nama didn’t provide a compatible source.",
            listOf("Back"), close = onClose,
        ) { onClose() }
        dialog == "subtitles" -> MediaDialog("Subtitles", null, listOf("Off") + tracks.map(TextTrack::name),
            initial = tracks.indexOfFirst { it.name == captionName } + 1, close = { dialog = null }) {
            val track = tracks.getOrNull(it - 1)
            captionName = track?.name
            selectCaption(track)
            dialog = null
            interaction++
        }
        dialog == "episodes" -> EpisodePicker(catalog.episodes, episode, { dialog = null }) {
            dialog = null
            onEpisode(it)
        }
    }
}

private fun clockTime(seconds: Long): String = if (seconds >= 3600) {
    String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
} else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
