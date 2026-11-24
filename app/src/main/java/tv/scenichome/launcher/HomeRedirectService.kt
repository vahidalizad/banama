package tv.scenichome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class HomeRedirectService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0) return
        val prefs = getSharedPreferences("home_redirect", MODE_PRIVATE)
        val previousBoot = prefs.getInt("boot_count", boot)
        prefs.edit().putInt("boot_count", boot).apply()
        // Google TV can already be on screen before TCL binds the service after a restart.
        if (previousBoot != boot) openHome()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (isGoogleTvHome(event.packageName, event.className)) openHome()
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onInterrupt() = Unit
}
