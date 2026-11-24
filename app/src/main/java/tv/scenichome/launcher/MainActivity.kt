package tv.scenichome.launcher

import android.app.role.RoleManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

internal data class TvApp(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val removable: Boolean,
)

class MainActivity : ComponentActivity() {
    internal val media by lazy { MediaStore(applicationContext) }
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private val requestHomeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            refreshHomeRoute()
            showNotice(homeStatus)
        }
    }
    private var updateApps: Job? = null
    private var homeLaunches by mutableIntStateOf(0)

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
    internal var notice by mutableStateOf<String?>(null)
        private set
    internal var homeStatus by mutableStateOf("")
        private set
    internal var keepHomeOnBack by mutableStateOf(false)
        private set

    internal var subtitlePreference: String?
        get() = prefs.getString("subtitle_preference", null)
        set(value) { prefs.edit().putString("subtitle_preference", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinned = readPackages("pinned")
        recent = readPackages("recent")
        setContent { key(homeLaunches) { ScenicHome(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeLaunches++
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRoute()
        foreground = true
        refreshApps()
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

    @Suppress("DEPRECATION")
    private fun refreshHomeRoute() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        val opensHere = target?.activityInfo?.packageName == packageName
        val selected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        val launchedForHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        keepHomeOnBack = opensHere || selected || launchedForHome
        val destination = target?.activityInfo?.applicationInfo
        val label = when (destination?.packageName) {
            null, "android" -> null
            "com.google.android.apps.tv.launcherx" -> "Google TV"
            else -> destination.loadLabel(packageManager).toString()
        }
        homeStatus = homeRouteDescription(opensHere, selected, label)
    }

    internal fun defaultHomeSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                val requested = runCatching {
                    requestHomeRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }
                if (requested.isSuccess) return
            }
        }
        if (!send(Intent(Settings.ACTION_HOME_SETTINGS))) {
            showNotice("This TV doesn't offer a Home app picker. $homeStatus")
        }
    }

    internal fun homeButtonSettings() {
        if (!send(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            showNotice("Open System → Accessibility in TV settings.")
        }
    }

    internal fun searchYouTube(query: String) {
        val url = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query).build()
        val browser = Intent(Intent.ACTION_VIEW, url)
        if (send(Intent(browser).setPackage("com.google.android.youtube.tv")) || send(browser)) return
        val installed = apps.firstOrNull { it.pkg == "com.google.android.youtube.tv" }
        if (installed != null) open(installed) else showNotice("YouTube couldn't be opened.")
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
