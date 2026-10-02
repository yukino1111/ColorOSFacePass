package eu.rafareborn.biometricbypass.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log

object ConfigClient {
    private const val TAG = "BiometricBypass"
    private const val URI_STRING = "content://eu.rafareborn.biometricbypass.config"
    private const val TTL_MS = 500L

    @Volatile
    private var lastFetchTime = 0L

    @Volatile
    var cachedMode = "whitelist"
        private set

    @Volatile
    var cachedPackages: Set<String> = emptySet()
        private set

    @Volatile
    var cachedHideDialog = false
        private set

    fun refresh(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFetchTime < TTL_MS && lastFetchTime != 0L) {
            return
        }
        runCatching {
            val uri = Uri.parse(URI_STRING)
            val bundle = context.contentResolver.call(uri, "getConfig", null, null)
            if (bundle != null) {
                cachedMode = bundle.getString("mode", "whitelist") ?: "whitelist"
                cachedPackages = bundle.getStringArrayList("packages")?.toSet() ?: emptySet()
                cachedHideDialog = bundle.getBoolean("hide_dialog", false)
                lastFetchTime = now
                Log.i(TAG, "ConfigClient refreshed: mode=$cachedMode, count=${cachedPackages.size}, hideDialog=$cachedHideDialog")
            }
        }.onFailure {
            Log.w(TAG, "ConfigClient failed to fetch via provider: ${it.message}")
        }
    }

    fun isAllowed(context: Context, packageName: String): Boolean {
        refresh(context)
        val inList = cachedPackages.contains(packageName)
        val allowed = when (cachedMode) {
            "whitelist" -> inList
            "blacklist" -> !inList
            else -> true
        }
        Log.i(TAG, "isAllowed pkg=$packageName mode=$cachedMode inList=$inList -> $allowed")
        return allowed
    }

    fun isHideDialog(context: Context): Boolean {
        refresh(context)
        return cachedHideDialog
    }
}
