package tv.scenichome.launcher

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import java.util.Locale

internal val savedResolutions = setOf(2160, 1080, 720, 480)

internal fun isDecoderError(code: Int) = code in
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

internal fun releasePlaybackProblem(code: Int?): String = when (code) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "This device couldn’t decode this release."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "The player couldn’t read this file’s format."
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "The server couldn’t serve the requested part of the file."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The player couldn’t read the release from the server."
    null -> "The player couldn’t prepare this release in time."
    else -> "The player couldn’t open this release."
}

internal fun videoResolution(width: Int, height: Int): Int = when {
    isUhd(width, height) -> 2160
    width >= 2560 && height < 1440 -> 1440
    width >= 1920 && height < 1080 -> 1080
    width >= 1280 && height < 720 -> 720
    width >= 848 && height < 480 -> 480
    else -> height
}

internal fun rankResolutions(available: List<Int?>, target: Int): List<Int?> =
    available.distinct().sortedWith(compareBy(
        { if (it == null) 2 else if (it <= target) 0 else 1 },
        { if (it == null) 0 else if (it <= target) target - it else it - target },
    ))

internal val Int?.resolutionLabel get() = when (this) {
    null -> "Other releases"
    2160 -> "4K"
    else -> "${this}p"
}

internal data class VideoQuality(
    val resolution: Int?,
    val encoder: String,
    val streamTrack: TrackSelectionOverride? = null,
    val bitrate: Int = 0,
    val download: DownloadVideo? = null,
) {
    val label get() = encoder.ifBlank { if (download == null) "Stream" else "Direct file" }
    val details get() = if (download == null) {
        if (bitrate > 0) "HLS · ${String.format(Locale.ROOT, "%.2f", bitrate / 1_000_000.0)} Mbps" else "HLS"
    } else {
        listOf(download.edition, download.container.uppercase(Locale.ROOT).ifBlank { "Direct file" })
            .filter(String::isNotBlank).joinToString(" · ")
    }

    fun sameVariant(other: VideoQuality) =
        streamTrack == other.streamTrack || (resolution == other.resolution && encoder == other.encoder && bitrate == other.bitrate)
}

private val fileSizePattern = Regex("""(?i)([0-9]+(?:\.[0-9]+)?)\s*(B|[KMGT]i?B)?""")

private fun fileSizeBytes(size: String): Double? {
    val match = fileSizePattern.matchEntire(size.trim()) ?: return null
    val unit = match.groupValues[2].uppercase(Locale.ROOT)
    val power = "KMGT".indexOf(unit.firstOrNull() ?: 'B') + 1
    return match.groupValues[1].toDoubleOrNull()?.times(Math.pow(if ('I' in unit) 1024.0 else 1000.0, power.toDouble()))
}

internal fun List<VideoQuality>.inPickerOrder() = sortedWith(
    compareByDescending<VideoQuality> { it.resolution }
        .thenByDescending { it.download?.size?.let(::fileSizeBytes) }
        .thenByDescending { it.bitrate },
)

internal fun DownloadVideo.videoQuality() = VideoQuality(resolution, encoder, download = this)

@OptIn(UnstableApi::class)
internal fun Tracks.videoQualities(width: Int = 0, height: Int = 0): List<VideoQuality> = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    .flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            val format = group.getTrackFormat(index)
            if (!group.isTrackSupported(index, group.isTrackSelected(index))) return@mapNotNull null
            val resolution = when {
                format.height > 0 -> videoResolution(format.width, format.height)
                height > 0 && group.isTrackSelected(index) -> videoResolution(width, height)
                else -> null
            }
            val codec = when (format.sampleMimeType ?: MimeTypes.getVideoMediaMimeType(format.codecs)) {
                MimeTypes.VIDEO_H264 -> "H.264"
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                else -> format.codecs.orEmpty()
            }
            VideoQuality(resolution, codec,
                TrackSelectionOverride(group.mediaTrackGroup, index), format.bitrate)
        }
    }.sortedByDescending { it.bitrate }.distinctBy { Triple(it.resolution, it.encoder, it.bitrate) }

@Composable
internal fun QualityPicker(
    choices: List<VideoQuality>,
    current: VideoQuality?,
    filesUnavailable: Boolean,
    close: () -> Unit,
    select: (VideoQuality) -> Unit,
) {
    val rows = remember(choices) { choices.inPickerOrder() }
    var focused by remember { mutableStateOf<String?>(null) }
    BanamaDialog(close) {
        Column(
            Modifier.widthIn(max = 740.dp).fillMaxWidth(.9f).heightIn(max = 480.dp)
                .background(panel, tileShape).border(1.dp, edge, tileShape).padding(24.dp)
                .guardDialogSelection { focused },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Playback quality", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            if (filesUnavailable) Text("File releases could not be loaded.", color = secondary, fontSize = 15.sp)
            LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(rows) { index, choice ->
                    val active = choice == current
                    FocusSurface(
                        "quality/$index", FocusMove("quality/0", 0), { focused = it }, { select(choice) },
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics { selected = active },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            QualityBadge(choice.resolution)
                            Column(Modifier.weight(1f)) {
                                Text(choice.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(choice.details, color = LocalContentColor.current.copy(alpha = .82f), fontSize = 16.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                choice.download?.size?.takeIf(String::isNotBlank)?.let {
                                    Text(it, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                }
                                if (active) Text("Playing", fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityBadge(resolution: Int?) {
    val badge = when (resolution) {
        1080 -> "Full HD"
        720 -> "HD"
        else -> resolution?.resolutionLabel
    }
    Box(Modifier.width(68.dp), contentAlignment = Alignment.Center) {
        if (badge != null) {
            Text(badge, Modifier.fillMaxWidth().border(1.dp, LocalContentColor.current.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(vertical = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
