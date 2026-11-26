package tv.scenichome.launcher

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.LanguageFeatureSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.Locale

internal const val BILINGUAL_SUBTITLES = "fa+en"

@OptIn(UnstableApi::class)
internal fun Caption.subtitle(): MediaItem.SubtitleConfiguration {
    val path = httpsUri(url)?.path.orEmpty().lowercase(Locale.ROOT)
    val mime = when {
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLanguage(language).build()
}

private val languageCode = Regex("[a-z]{2,3}")
private val unknownLanguages = setOf("und", "unk", "zxx")

private fun knownLanguage(language: String?) = language.orEmpty().trim().lowercase(Locale.ROOT)
    .substringBefore('-').substringBefore('_').takeIf { languageCode.matches(it) && it !in unknownLanguages }

internal fun subtitlePreferenceKey(language: String?, name: String): String =
    when (val primary = knownLanguage(language)) {
        "eng" -> "en"
        "fas", "per" -> "fa"
        null -> when (name.trim().lowercase(Locale.ROOT).takeWhile(Char::isLetter)) {
            "english" -> "en"
            "persian", "farsi", "فارسی", "فارسي" -> "fa"
            else -> "label:$name"
        }
        else -> primary
    }

private val genericSubtitleLabel = Regex("""(?i)(?:subtitle|track|undetermined|unknown)(?:\s+\d+)?""")

internal fun subtitleDisplayName(language: String?, label: String?): String? {
    val named = label?.trim()?.takeIf { it.isNotEmpty() && !genericSubtitleLabel.matches(it) }
    val primary = knownLanguage(language)
    return named ?: primary?.let {
        val normalized = subtitlePreferenceKey(it, "")
        Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.getDefault()).ifBlank { normalized }
    }
}

internal data class TextTrack(val group: Tracks.Group, val index: Int, val name: String, val preference: String) {
    val override get() = TrackSelectionOverride(group.mediaTrackGroup, index)
}

internal fun Tracks.textTracks(): List<TextTrack> = groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
    (0 until group.length).filter(group::isTrackSupported).mapNotNull { index ->
        val format = group.getTrackFormat(index)
        val name = subtitleDisplayName(format.language, format.label) ?: return@mapNotNull null
        TextTrack(group, index, name, subtitlePreferenceKey(format.language, name))
    }
}

internal fun List<TextTrack>.bilingualTracks(): List<TextTrack> {
    for (english in filter { it.preference == "en" }) {
        val persian = firstOrNull { it.preference == "fa" && it.group.mediaTrackGroup != english.group.mediaTrackGroup }
        if (persian != null) return listOf(english, persian)
    }
    return emptyList()
}

@OptIn(UnstableApi::class)
internal class CaptionRenderers(context: Context, private val secondary: TextOutput) : DefaultRenderersFactory(context) {
    @Volatile var route = DecoderRoute()

    init {
        setEnableDecoderFallback(true)
        setMediaCodecSelector { mime, secure, tunneling ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            val current = route
            if (!secure && !tunneling && (mime == current.softwareVideo || mime == current.softwareAudio)) {
                if (BuildConfig.BUNDLED_HEVC) emptyList() else codecs.filter { it.softwareOnly }
            } else codecs.sortedByDescending { it.hardwareAccelerated }
        }
    }

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        if (!BuildConfig.BUNDLED_HEVC) return

        val renderer = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer")
            .asSubclass(Renderer::class.java)
        val int = Int::class.javaPrimitiveType!!
        val constructor = renderer.getConstructor(Long::class.javaPrimitiveType!!, Handler::class.java,
            VideoRendererEventListener::class.java, int, int, int, int)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        out.add(constructor.newInstance(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50, threads, 4, 4))
    }

    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out)
        if (BuildConfig.BUNDLED_HEVC) {
            val renderer = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer")
                .asSubclass(Renderer::class.java)
            out.add(renderer.getConstructor(Handler::class.java, AudioRendererEventListener::class.java, AudioSink::class.java)
                .newInstance(eventHandler, eventListener, audioSink))
        }
    }

    override fun buildTextRenderers(
        context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>,
    ) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        out.add(TextRenderer(secondary, outputLooper))
    }
}

@OptIn(UnstableApi::class)
internal class CaptionTrackSelector(context: Context) : DefaultTrackSelector(context) {
    @Volatile private var captions = emptyList<TrackSelectionOverride>()

    fun selectCaptions(tracks: List<TextTrack>) {
        val next = tracks.map(TextTrack::override)
        if (captions == next) return
        captions = next
        invalidate(null)
    }

    override fun selectAllTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ) {
        super.selectAllTracks(definitions, mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
        val textRenderers = definitions.indices.filter { mappedTrackInfo.getRendererType(it) == C.TRACK_TYPE_TEXT }
        val groupRenderer = textRenderers.first()
        val groups = mappedTrackInfo.getTrackGroups(groupRenderer)
        val selected = captions
        // Media3 maps both caption groups to the first matching renderer. Each can still read its own sample stream.
        textRenderers.forEachIndexed { index, renderer ->
            val track = selected.getOrNull(index)
            definitions[renderer] = track?.takeIf { groups.indexOf(it.mediaTrackGroup) >= 0 }?.let {
                ExoTrackSelection.Definition(it.mediaTrackGroup, it.trackIndices.single())
            }
        }
    }
}

private class CaptionTypeface(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) { paint.typeface = face }
    override fun updateMeasureState(paint: TextPaint) { paint.typeface = face }
}

@OptIn(UnstableApi::class)
internal fun displayCaptions(primary: List<Cue>, secondary: List<Cue>, persian: Typeface, primaryIsPersian: Boolean): List<Cue> {
    fun Cue.Builder.persianText() = getText()?.let { text ->
        SpannableStringBuilder(text).apply {
            setSpan(CaptionTypeface(persian), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun styled(cue: Cue, usePersianFont: Boolean): Cue = cue.buildUpon().apply {
        clearWindowColor()
        setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        val original = cue.text
        if (original is Spanned) {
            val clean = SpannableStringBuilder(original)
            clean.getSpans(0, clean.length, Any::class.java).filterNot { it is LanguageFeatureSpan }.forEach(clean::removeSpan)
            setText(clean)
        }
        if (usePersianFont) persianText()?.let { setText(it) }
    }.build()

    if (secondary.isEmpty()) return primary.map { styled(it, primaryIsPersian) }
    if (primary.isEmpty()) return secondary.map { styled(it, true) }

    val englishText = primary.filter { !it.text.isNullOrBlank() }
    val persianText = secondary.filter { !it.text.isNullOrBlank() }
    if (englishText.isEmpty() || persianText.isEmpty()) {
        return primary.map { styled(it, false) }.ifEmpty { secondary.map { styled(it, true) } }
    }

    // A single text layout lets wrapped lines grow upward from the existing English cue position.
    val stacked = SpannableStringBuilder()
    val lines = persianText.distinctBy { it.text.toString() }.map { styled(it, true) } +
        englishText.distinctBy { it.text.toString() }.map { styled(it, false) }
    lines.forEach { cue ->
        if (stacked.isNotEmpty()) stacked.append('\n')
        stacked.append(cue.text)
    }
    return listOf(styled(englishText.last(), false).buildUpon().setText(stacked).build())
}
