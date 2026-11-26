package tv.scenichome.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonsPhotosTest {
    @Test
    fun keepsCreditedUhdPhotosAndRejectsSmallerOriginals() {
        val pages = listOf(
            """{"ns":14,"pageid":1}""",
            page(2),
            page(3, height = 2159),
            page(4, host = "example.org"),
            page(5, license = "FAL"),
            page(6, width = 3839),
            page(7, originalWidth = 1920),
            page(8, originalHeight = 1080),
            page(2),
        )
        val photos = commonsPhotos("""{"query":{"pages":[${pages.joinToString(",")}]}}""")
        assertEquals(listOf(2L), photos.map(CommonsPhoto::id))
        assertEquals("Seal.jpg", photos.single().title)
        assertEquals("Photographer", photos.single().artist)
        assertEquals("CC BY-SA 4.0", photos.single().license)
    }

    @Test
    fun frostBlursThePhotoInBothDirections() {
        val black = 0xFF000000.toInt()
        val pixels = IntArray(81) { black }.apply { this[40] = 0xFFFFFFFF.toInt() }
        val frosted = frostedPixels(pixels, width = 9, height = 9)

        assertEquals(0xFF050505.toInt(), frosted[40])
        assertEquals(frosted[40], frosted[10])
        assertEquals(black, frosted[0])
    }

    private fun page(
        id: Int,
        width: Int = 3840,
        height: Int = 2160,
        originalWidth: Int = width,
        originalHeight: Int = height,
        host: String = "thumb.wikimedia.org",
        license: String = "CC BY-SA 4.0",
    ) =
        """{"ns":6,"pageid":$id,"title":"File:Seal.jpg","imageinfo":[{
          "mime":"image/jpeg","thumbwidth":$width,"thumbheight":$height,
          "width":$originalWidth,"height":$originalHeight,
          "descriptionurl":"https://commons.wikimedia.org/wiki/File:Seal.jpg","thumburl":"https://$host/seal.jpg",
          "extmetadata":{"Artist":{"value":"Photographer"},"LicenseShortName":{"value":"$license"},
          "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"}}}]}"""
}
