package tv.scenichome.launcher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BanamaReleaseTest {
    private val sha = "a".repeat(40)
    private val bytes = "Banama".encodeToByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun release(code: Int, tag: String = "banama-$sha", name: String = "Banama.apk") = JSONObject()
        .put("tag_name", tag)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", name).put("state", "uploaded").put("label", "Banama 1.2 (build $code)")
            .put("size", bytes.size).put("digest", "sha256:$digest")))

    @Test fun selectsHighestStableStandardApkByAndroidVersionCode() {
        val releases = JSONArray().put(release(25)).put(release(32)).put(release(28))
            .put(release(97, tag = "launcher-$sha")).put(release(98, name = "Banama-bundled-hevc.apk"))
            .put(release(99).put("prerelease", true)).put(release(100).put("draft", true))
        val selected = newestBanamaRelease(releases, 26)!!
        assertEquals(32, selected.code)
        assertEquals("https://github.com/vahidalizad/banama/releases/download/banama-$sha/Banama.apk", selected.downloadUrl())
        assertNull(newestBanamaRelease(releases, 32))
    }

    @Test fun skipsLegacyOrIncompleteAssets() {
        for ((key, value) in listOf("label" to "", "state" to "open", "digest" to "sha256:bad", "size" to 0)) {
            val release = release(30)
            release.getJSONArray("assets").getJSONObject(0).put(key, value)
            assertNull(newestBanamaRelease(JSONArray().put(release), 26))
        }
    }

    @Test fun onlyAnIntactApkCanBeCommitted() {
        val release = newestBanamaRelease(JSONArray().put(release(30)), 26)!!
        val output = ByteArrayOutputStream()
        runBlocking { release.copyVerified(bytes.inputStream(), output) }
        assertArrayEquals(bytes, output.toByteArray())
        for (damaged in listOf(bytes.copyOf(2), bytes + 0, bytes.reversedArray())) {
            assertThrows(IOException::class.java) {
                runBlocking { release.copyVerified(damaged.inputStream(), ByteArrayOutputStream()) }
            }
        }
    }
}
