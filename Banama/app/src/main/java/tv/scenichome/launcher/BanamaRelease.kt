package tv.scenichome.launcher

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

private const val repository = "vahidalizad/banama"
private val releaseTag = Regex("""banama-[0-9a-f]{40}""")
private val apkLabel = Regex("""Banama .+ \(build ([1-9][0-9]*)\)""")
private val githubDigest = Regex("""sha256:([0-9a-f]{64})""")

internal data class BanamaRelease(val code: Int, val tag: String, val size: Long, val sha256: String) {
    fun downloadUrl() = "https://github.com/$repository/releases/download/$tag/Banama.apk"
}

internal fun newestBanamaRelease(releases: JSONArray, installedCode: Int): BanamaRelease? =
    releases.objects().mapNotNull { release ->
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null
        val tag = release.text("tag_name")
        if (!releaseTag.matches(tag)) return@mapNotNull null
        val apk = release.optJSONArray("assets").objects().firstOrNull {
            it.text("name") == "Banama.apk" && it.text("state") == "uploaded"
        } ?: return@mapNotNull null
        val code = apkLabel.matchEntire(apk.text("label"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        if (code <= installedCode) return@mapNotNull null
        val hash = githubDigest.matchEntire(apk.text("digest"))?.groupValues?.get(1) ?: return@mapNotNull null
        val size = apk.optLong("size")
        if (size > 0) BanamaRelease(code, tag, size, hash) else null
    }.maxByOrNull { it.code }

internal inline fun <T> readGithub(url: String, read: (InputStream) -> T): T {
    val connection = URL(url).openConnection() as HttpsURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("User-Agent", "Banama/${BuildConfig.VERSION_NAME}")
    try {
        return connection.inputStream.use(read)
    } finally {
        connection.disconnect()
    }
}

internal suspend fun findBanamaUpdate(): BanamaRelease? {
    var newest: BanamaRelease? = null
    var page = 1
    do {
        currentCoroutineContext().ensureActive()
        val releases = readGithub("https://api.github.com/repos/$repository/releases?per_page=100&page=$page") {
            JSONArray(it.readBounded(4 * 1024 * 1024).decodeToString())
        }
        newestBanamaRelease(releases, newest?.code ?: BuildConfig.VERSION_CODE)?.let { newest = it }
        page++
    } while (releases.length() == 100)
    return newest
}

internal suspend fun BanamaRelease.copyVerified(input: InputStream, output: OutputStream) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > size) throw IOException("APK exceeds its release size")
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    if (total != size || digest.digest().toHexString() != sha256) throw IOException("Incomplete or damaged APK")
}
