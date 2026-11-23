package tv.scenichome.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal data class TvApp(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val removable: Boolean,
)

class MainActivity : ComponentActivity() {
    internal val media by lazy { MediaStore(applicationContext) }
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private val gallery by lazy { PhotoGallery(applicationContext) }
    private val rotationEngine by lazy { PhotoRotation(ZonedDateTime::now, gallery::next) }
    private val photoLock = Mutex()
    private lateinit var rotation: RotationState
    private var updatePhotos: Job? = null
    private var updateApps: Job? = null

    internal var apps by mutableStateOf<List<TvApp>>(emptyList())
        private set
    internal var appsLoaded by mutableStateOf(false)
        private set
    internal var pinned by mutableStateOf<List<String>>(emptyList())
        private set
    internal var recent by mutableStateOf<List<String>>(emptyList())
        private set
    internal var foreground by mutableStateOf(false)
        private set
    internal var scene by mutableStateOf<LoadedScene?>(null)
        private set
    internal var paused by mutableStateOf(false)
        private set
    internal var photoBusy by mutableStateOf(false)
        private set
    internal var notice by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinned = readPackages("pinned")
        recent = readPackages("recent")
        paused = prefs.getBoolean("paused", false)
        rotation = RotationState(
            prefs.getString("photo", Scene.fallback.id) ?: Scene.fallback.id,
            prefs.getLong("photo_hour", ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).toEpochSecond()),
            paused,
        )
        setContent { ScenicHome(this) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refreshApps()
        updatePhotos?.cancel()
        updatePhotos = lifecycleScope.launch {
            if (scene == null) {
                val id = rotation.photoId
                val image = withContext(Dispatchers.IO) { gallery.restore(id) ?: gallery.restore(Scene.fallback.id) }
                scene = image
                if (image != null) saveRotation(rotation.copy(photoId = image.scene.id))
            }
            while (isActive) {
                rotate()
                val now = ZonedDateTime.now()
                val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                delay(Duration.between(now, nextHour).toMillis().coerceIn(50L, 60_000L))
            }
        }
    }

    override fun onPause() {
        foreground = false
        updatePhotos?.cancel()
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

    private fun refreshApps() {
        updateApps?.cancel()
        updateApps = lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { discoverApps() }
            apps = updated
            appsLoaded = true
        }
    }

    @Suppress("DEPRECATION")
    private fun discoverApps(): List<TvApp> {
        val pm = packageManager
        val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(tv, PackageManager.MATCH_ALL).mapNotNull { match ->
            val info = match.activityInfo
            if (info.packageName == packageName || !info.exported || !info.enabled) return@mapNotNull null
            val system = info.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            TvApp(
                info.packageName,
                match.loadLabel(pm).toString().trim().ifBlank { info.packageName },
                ComponentName(info.packageName, info.name),
                removable = !system,
            )
        }.sortedWith { a, b ->
            collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.pkg.compareTo(b.pkg)
        }.distinctBy { it.pkg }
    }

    internal suspend fun iconFor(app: TvApp): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val icon = packageManager.getActivityIcon(app.component)
            val image = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, image.width, image.height)
            icon.draw(Canvas(image))
            image
        }.getOrNull()
    }

    internal fun open(app: TvApp) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!send(intent)) {
            showNotice("${app.label} couldn't be opened.")
            refreshApps()
            return
        }
        recent = (listOf(app.pkg) + recent).distinct().take(12)
        savePackages("recent", recent)
    }

    internal fun getApps() {
        val store = "com.android.vending"
        val market = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET).setPackage(store)
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps&c=apps")).setPackage(store)
        val openStore = packageManager.getLeanbackLaunchIntentForPackage(store)
            ?: packageManager.getLaunchIntentForPackage(store)
        if (listOfNotNull(openStore, market, search).any(::send)) return
        showNotice("Google Play couldn't be opened. Find apps from the Google TV Apps tab.")
    }

    internal fun tvSettings() {
        if (!send(Intent(Settings.ACTION_SETTINGS))) showNotice("TV settings couldn't be opened.")
    }

    internal fun searchYouTube(query: String) {
        val url = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query).build()
        val browser = Intent(Intent.ACTION_VIEW, url)
        if (send(Intent(browser).setPackage("com.google.android.youtube.tv")) || send(browser)) return
        val installed = apps.firstOrNull { it.pkg == "com.google.android.youtube.tv" }
        if (installed != null) open(installed) else showNotice("YouTube couldn't be opened.")
    }

    internal fun openPhotoSource() {
        val source = scene?.scene?.source ?: Scene.fallback.source
        if (!send(Intent(Intent.ACTION_VIEW, Uri.parse(source)))) showNotice("This TV has no browser for the photo page.")
    }

    internal fun appInfo(app: TvApp) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(app))
        if (!send(intent)) showNotice("App info couldn't be opened.")
    }

    internal fun uninstall(app: TvApp) {
        if (!app.removable || apps.none { it.pkg == app.pkg && it.removable }) return
        val intent = Intent(Intent.ACTION_DELETE, packageUri(app))
        if (!send(intent)) {
            showNotice("Open App info to manage the app.")
            appInfo(app)
        }
    }

    internal fun togglePin(app: TvApp) {
        pinned = when {
            app.pkg in pinned -> pinned - app.pkg
            pinned.count { pkg -> apps.any { it.pkg == pkg } } < MAX_FAVORITES ->
                pinned.filter { pkg -> apps.any { it.pkg == pkg } } + app.pkg
            else -> {
                showNotice("Unpin a favorite to make room.")
                return
            }
        }
        savePackages("pinned", pinned)
    }

    internal fun movePin(app: TvApp, offset: Int) {
        val order = pinned.filter { pkg -> apps.any { it.pkg == pkg } }.toMutableList()
        val index = order.indexOf(app.pkg)
        if (index < 0 || index + offset !in order.indices) return
        order.add(index + offset, order.removeAt(index))
        pinned = order
        savePackages("pinned", pinned)
    }

    internal fun togglePause() {
        paused = !paused
        saveRotation(rotation.copy(paused = paused))
        if (!paused) lifecycleScope.launch { rotate() }
    }

    internal fun nextPhoto() {
        if (!photoBusy) lifecycleScope.launch { rotate(manual = true) }
    }

    private suspend fun rotate(manual: Boolean = false) = photoLock.withLock {
        val state = rotation
        val hour = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).toEpochSecond()
        if (!manual && (state.paused || state.hour == hour)) return@withLock
        photoBusy = true
        try {
            val updated = withContext(Dispatchers.IO) { rotationEngine.advance(state, manual) }
                ?: return@withLock
            if (!manual && rotation.paused) return@withLock
            updated.photo?.let { scene = it }
            saveRotation(updated.state.copy(paused = paused))
            if (manual && updated.photo == null) showNotice("No other photo is available right now.")
        } finally {
            photoBusy = false
        }
    }

    private fun saveRotation(state: RotationState) {
        rotation = state
        prefs.edit().putBoolean("paused", state.paused)
            .putString("photo", state.photoId).putLong("photo_hour", state.hour).apply()
    }

    private fun packageUri(app: TvApp) = Uri.fromParts("package", app.pkg, null)

    private fun send(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

    private fun readPackages(key: String): List<String> =
        prefs.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun savePackages(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString(",")).apply()
    }

    internal fun clearNotice() { notice = null }
    private fun showNotice(text: String) { notice = text }
}
