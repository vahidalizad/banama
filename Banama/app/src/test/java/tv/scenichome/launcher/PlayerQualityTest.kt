package tv.scenichome.launcher

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQualityTest {
    @Test
    fun retriesCompatibleDolbyVisionProfilesAsAnHevcBaseLayer() {
        assertTrue(hasDolbyVisionHevcBase("dvhe.08.06"))
        assertTrue(hasDolbyVisionHevcBase("dvh1.08.04"))
        assertFalse(hasDolbyVisionHevcBase("dvhe.05.06"))
        assertTrue(hasDolbyVisionHevcBase("dvhe.07.06"))
        assertFalse(hasDolbyVisionHevcBase("hvc1.08.06"))
    }

    @Test
    fun retriesHardwareBaseLayerBeforeSoftwareAndKeepsAudioIndependent() {
        val base = DecoderRoute().retry("video/dolby-vision", "dvhe.07.06")!!
        assertEquals(DecoderRoute(hevcBase = true), base)
        val video = base.retry("video/hevc", null)!!
        assertEquals("video/hevc", video.softwareVideo)
        assertEquals(null, video.retry("video/hevc", null))
        assertEquals(video.copy(softwareAudio = "audio/vnd.dts"), video.retry("audio/vnd.dts", null))
        assertEquals(null, DecoderRoute().retry("video/dolby-vision", "dvhe.05.06"))
    }

    @Test
    fun decoderAndRangeFailuresHaveDifferentExplanations() {
        assertEquals("This device couldn’t decode this release.",
            releasePlaybackProblem(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals("The server couldn’t serve the requested part of the file.",
            releasePlaybackProblem(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
    }

    @Test
    fun resolutionUsesTheFullWidthOfCroppedReleases() {
        assertEquals(2160, videoResolution(3840, 1600))
        assertEquals(1080, videoResolution(1920, 800))
        assertEquals(720, videoResolution(1280, 536))
        assertEquals(480, videoResolution(854, 360))
    }

    @Test
    fun pickerOrdersTiersBeforeSizesAndKeepsUnmeasuredStreamsAvailable() {
        fun file(tier: Int?, size: String) =
            DownloadVideo(0, 0, "", emptyList(), tier, size = size).videoQuality()
        val stream = VideoQuality(720, "H.264", bitrate = 4_000_000)
        val choices = listOf(file(480, "9 GB"), stream, file(720, "900 MB"), file(null, "40 GB"),
            file(720, "1.1 GiB"), file(2160, ""), file(720, "1024 MiB"))
        assertEquals(listOf(choices[5], choices[4], choices[6], choices[2], stream, choices[0], choices[3]),
            choices.inPickerOrder())
    }

    @Test
    fun exactTierWinsAndFallbackPrefersLowerResolutions() {
        assertEquals(listOf(720, 480, 1080, 2160, null), rankResolutions(listOf(2160, 480, 720, 1080, 720, null), 720))
        assertEquals(listOf(480, 1080, 2160), rankResolutions(listOf(2160, 480, 1080), 720))
        assertEquals(listOf(1080, 2160), rankResolutions(listOf(2160, 1080), 720))
    }
}
