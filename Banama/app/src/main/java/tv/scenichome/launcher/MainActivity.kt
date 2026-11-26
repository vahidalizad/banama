package tv.scenichome.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    internal val media by lazy { MediaStore(applicationContext) }
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    internal var foreground by mutableStateOf(false)
        private set
    internal var notice by mutableStateOf<String?>(null)
        private set

    internal var subtitlePreference: String?
        get() = prefs.getString("subtitle_preference", null)
        set(value) { prefs.edit().putString("subtitle_preference", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BanamaHome(this) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    internal fun searchYouTube(query: String) {
        val url = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query).build()
        val browser = Intent(Intent.ACTION_VIEW, url)
        if (send(Intent(browser).setPackage("com.google.android.youtube.tv")) || send(browser)) return
        val installed = packageManager.getLeanbackLaunchIntentForPackage("com.google.android.youtube.tv")
        if (installed == null || !send(installed)) notice = "YouTube couldn't be opened."
    }

    private fun send(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

    internal fun clearNotice() { notice = null }
}
