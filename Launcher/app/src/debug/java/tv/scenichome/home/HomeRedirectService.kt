package tv.scenichome.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class HomeRedirectService : AccessibilityService() {
    private var watch: HomeRedirectWatch? = null

    @Suppress("DEPRECATION")
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Settings.Global.getInt(contentResolver, Settings.Global.DEVICE_PROVISIONED, 0) == 0) return

        // shortcut: discover Home at bind time, refresh on package changes if live launcher swaps are needed.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.priority >= 0 }
            .map { it.activityInfo }
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val stockPackage = candidates.firstOrNull {
            it.packageName != packageName && it.applicationInfo.flags and systemFlags != 0
        }?.packageName ?: return
        val classes = candidates.filter { it.packageName == stockPackage }
            .flatMap { listOfNotNull(it.name, it.targetActivity) }.toSet()
        watch = HomeRedirectWatch(stockPackage, classes)
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(stockPackage) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (watch?.shouldRedirect(event.packageName, event.className, SystemClock.uptimeMillis()) != true) return
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onInterrupt() = Unit
}

internal class HomeRedirectWatch(private val stockPackage: String, private val classes: Set<String>) {
    private var lastRedirect = -600L

    fun shouldRedirect(windowPackage: CharSequence?, windowClass: CharSequence?, now: Long): Boolean {
        if (windowPackage?.toString() != stockPackage || windowClass?.toString() !in classes) return false
        if (now - lastRedirect < 600) return false
        lastRedirect = now
        return true
    }
}
