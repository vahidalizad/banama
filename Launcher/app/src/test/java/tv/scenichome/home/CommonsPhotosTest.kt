package tv.scenichome.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonsPhotosTest {
    @Test
    fun keepsCreditedLandscapeThumbnailsAndIgnoresOtherPages() {
        val pages = listOf(
            """{"ns":14,"pageid":1}""",
            page(2),
            page(3, height = 1500),
            page(4, host = "example.org"),
            page(5, license = "FAL"),
            page(2),
        )
        val photos = commonsPhotos("""{"query":{"pages":[${pages.joinToString(",")}]}}""")
        assertEquals(listOf(2L), photos.map(CommonsPhoto::id))
        assertEquals("Coast.jpg", photos.single().title)
        assertEquals("Photographer", photos.single().artist)
        assertEquals("CC BY-SA 4.0", photos.single().license)
    }

    private fun page(id: Int, height: Int = 800, host: String = "thumb.wikimedia.org", license: String = "CC BY-SA 4.0") =
        """{"ns":6,"pageid":$id,"title":"File:Coast.jpg","imageinfo":[{
          "mime":"image/jpeg","thumbwidth":1280,"thumbheight":$height,
          "descriptionurl":"https://commons.wikimedia.org/wiki/File:Coast.jpg","thumburl":"https://$host/coast.jpg",
          "extmetadata":{"Artist":{"value":"Photographer"},"LicenseShortName":{"value":"$license"},
          "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"}}}]}"""
}
