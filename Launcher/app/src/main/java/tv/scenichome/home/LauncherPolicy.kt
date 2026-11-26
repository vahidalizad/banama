package tv.scenichome.home

internal const val MAX_FAVORITES = 4

internal fun homeRouteDescription(opensHere: Boolean, roleHeld: Boolean, destination: String?): String = when {
    opensHere -> "Android opens BananaLauncher for Home."
    destination == null -> "Choose a default Home app in TV settings."
    roleHeld -> "BananaLauncher is selected. This TV sends Home to $destination."
    else -> "Android opens $destination for Home."
}

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
