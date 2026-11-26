package tv.scenichome.launcher

internal class MenuSelectPress {
    private var owner: Pair<Int, String>? = null

    fun consume(key: Int, down: Boolean, repeats: Int, action: String?): Boolean {
        if (down && repeats == 0) owner = action?.let { key to it }
        val matches = owner?.first == key && owner?.second == action
        if (!down) owner = null
        return !matches
    }
}
