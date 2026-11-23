package tv.scenichome.launcher

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal data class RotationState(val photoId: String, val hour: Long, val paused: Boolean)

internal data class PhotoChoice<T>(val id: String, val value: T)

internal class PhotoRotation<T>(
    private val now: () -> ZonedDateTime,
    private val nextPhoto: (String) -> PhotoChoice<T>?,
) {
    data class Update<T>(val state: RotationState, val photo: T?)

    fun advance(state: RotationState, manual: Boolean = false): Update<T>? {
        val hour = now().truncatedTo(ChronoUnit.HOURS).toEpochSecond()
        if (!manual && (state.paused || state.hour == hour)) return null

        val next = nextPhoto(state.photoId)
        return Update(state.copy(photoId = next?.id ?: state.photoId, hour = hour), next?.value)
    }
}

internal fun <T : Any> firstUsablePhoto(
    ids: Iterable<String>,
    current: String,
    load: (String) -> T?,
): PhotoChoice<T>? {
    for (id in ids) {
        if (id == current) continue
        val image = load(id) ?: continue
        return PhotoChoice(id, image)
    }
    return null
}

internal const val MAX_FAVORITES = 4

private val homeControlPackages = setOf("com.android.tv.settings", "com.android.settings", "com.android.vending")

internal fun dockApps(installed: List<String>, pinned: List<String>, recent: List<String>): List<String> {
    val available = installed.toSet()
    val suggestions = (recent + installed).filterNot { it in homeControlPackages }
    return (pinned + suggestions).distinct().filter { it in available }.take(MAX_FAVORITES)
}

internal fun focusAfterRefresh(previous: String?, targets: List<String>): String? =
    previous?.takeIf { it in targets } ?: targets.firstOrNull()

internal class MenuSelectPress {
    private var owner: Pair<Int, String>? = null

    fun consume(key: Int, down: Boolean, repeats: Int, action: String?): Boolean {
        if (down && repeats == 0) owner = action?.let { key to it }
        val matches = owner?.first == key && owner?.second == action
        if (!down) owner = null
        return !matches
    }
}
