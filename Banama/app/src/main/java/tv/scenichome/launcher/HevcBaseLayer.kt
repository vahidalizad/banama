package tv.scenichome.launcher

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput

internal fun hasDolbyVisionHevcBase(codecs: String?): Boolean {
    val codec = codecs.orEmpty().split('.')
    return codec.first() in setOf("dvhe", "dvh1") && codec.getOrNull(1)?.toIntOrNull() in setOf(7, 8)
}

internal data class DecoderRoute(
    val hevcBase: Boolean = false,
    val softwareVideo: String? = null,
    val softwareAudio: String? = null,
) {
    @OptIn(UnstableApi::class)
    fun retry(mime: String?, codecs: String?): DecoderRoute? = when {
        mime == MimeTypes.VIDEO_DOLBY_VISION && !hevcBase && hasDolbyVisionHevcBase(codecs) -> copy(hevcBase = true)
        mime == MimeTypes.VIDEO_DOLBY_VISION -> null
        MimeTypes.isVideo(mime) && softwareVideo == null -> copy(softwareVideo = mime)
        MimeTypes.isAudio(mime) && softwareAudio == null -> copy(softwareAudio = mime)
        else -> null
    }
}

@OptIn(UnstableApi::class)
internal class HevcBaseLayerExtractors(
    private val useBaseLayer: () -> Boolean,
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory by delegate {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> {
        if (!useBaseLayer()) return extractors
        return extractors.map { extractor ->
            object : ForwardingExtractor(extractor) {
                override fun init(output: ExtractorOutput) {
                    super.init(object : ForwardingExtractorOutput(output) {
                        override fun track(id: Int, type: Int): TrackOutput {
                            val track = super.track(id, type)
                            if (type != C.TRACK_TYPE_VIDEO) return track
                            return object : ForwardingTrackOutput(track) {
                                override fun format(format: Format) {
                                    val base = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                                        hasDolbyVisionHevcBase(format.codecs)
                                    ) format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
                                    else format
                                    super.format(base)
                                }
                            }
                        }
                    })
                }
            }
        }.toTypedArray()
    }
}
