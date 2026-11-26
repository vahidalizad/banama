package tv.scenichome.launcher

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BanamaUpdater(private val app: Application) : AndroidViewModel(app) {
    var available by mutableStateOf<BanamaRelease?>(null)
        private set
    var installing by mutableStateOf(false)
        private set
    var confirmation by mutableStateOf<Intent?>(null)
        private set

    private val installer get() = app.packageManager.packageInstaller
    private val statusAction = "${app.packageName}.UPDATE_STATUS"
    private var listening = false
    @Volatile private var sessionId = -1

    suspend fun check() {
        if (BuildConfig.DEBUG || BuildConfig.BUNDLED_HEVC || installing) return
        available = withContext(Dispatchers.IO) {
            try {
                findBanamaUpdate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    fun install() {
        val release = available ?: return
        if (installing) return
        installing = true
        viewModelScope.launch {
            try {
                if (!listening) {
                    ContextCompat.registerReceiver(app, status, IntentFilter(statusAction), ContextCompat.RECEIVER_NOT_EXPORTED)
                    listening = true
                }
                withContext(Dispatchers.IO) { stage(release) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finish("Couldn’t prepare the update. Check the connection and free space, then try again.")
            }
        }
    }

    private suspend fun stage(release: BanamaRelease) {
        // A killed process can leave an unfinished download in the system installer.
        installer.mySessions.filter { it.appPackageName == app.packageName }.forEach { installer.abandonSession(it.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(release.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        sessionId = id
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, release.size).use { output ->
                    readGithub(release.downloadUrl()) { release.copyVerified(it, output) }
                    session.fsync(output)
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(app, id, Intent(statusAction).setPackage(app.packageName), flags)
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(id)
        }
    }

    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId || !installing) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirmation == null) cancelConfirmation()
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    available = null
                    finish()
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
                PackageInstaller.STATUS_FAILURE_STORAGE -> finish("Free up space on the TV, then select Update again.")
                PackageInstaller.STATUS_FAILURE_BLOCKED -> finish("This TV blocked the update. Check its app installation settings.")
                PackageInstaller.STATUS_FAILURE_CONFLICT -> finish("This update doesn’t match the installed copy of Banama.")
                else -> finish("Android couldn’t install the update. Select Update to try again.")
            }
        }
    }

    fun showConfirmation(activity: Activity) {
        val intent = confirmation ?: return
        confirmation = null
        try {
            activity.startActivity(intent)
        } catch (_: RuntimeException) {
            cancelConfirmation()
        }
    }

    private fun cancelConfirmation() {
        runCatching { installer.abandonSession(sessionId) }
        finish("The TV couldn’t open the update confirmation. Select Update to try again.")
    }

    private fun finish(message: String? = null) {
        sessionId = -1
        confirmation = null
        installing = false
        if (message != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    override fun onCleared() {
        if (listening) app.unregisterReceiver(status)
        super.onCleared()
    }
}
