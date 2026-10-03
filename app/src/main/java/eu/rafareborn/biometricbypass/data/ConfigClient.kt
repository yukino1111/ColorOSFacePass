package eu.rafareborn.biometricbypass.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import eu.rafareborn.biometricbypass.TAG
import eu.rafareborn.biometricbypass.module

object ConfigClient {
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

    fun refresh(context: Context? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFetchTime < TTL_MS && lastFetchTime != 0L) {
            return
        }

        var fetched = false

        // 1. Try ContentProvider (fast IPC if UI app is running)
        if (context != null) {
            runCatching {
                val uri = Uri.parse(URI_STRING)
                val bundle = context.contentResolver.call(uri, "getConfig", null, null)
                if (bundle != null) {
                    cachedMode = bundle.getString("mode", "whitelist") ?: "whitelist"
                    cachedPackages = bundle.getStringArrayList("packages")?.toSet() ?: emptySet()
                    cachedHideDialog = bundle.getBoolean("hide_dialog", false)
                    lastFetchTime = now
                    fetched = true
                    runCatching {
                        module.log(Log.INFO, TAG, "ConfigClient refreshed via ContentProvider: mode=$cachedMode, count=${cachedPackages.size}, hideDialog=$cachedHideDialog")
                    }
                }
            }.onFailure {
                runCatching {
                    module.log(Log.WARN, TAG, "ConfigClient provider fetch failed: ${it.message}")
                }
            }
        }

        // 2. Fallback to Xposed remote preferences (persisted across reboots by LSPosed daemon)
        if (!fetched) {
            runCatching {
                val prefs = module.getRemotePreferences("config")
                cachedMode = prefs.getString("mode", "whitelist") ?: "whitelist"
                cachedPackages = prefs.getStringSet("packages", emptySet()) ?: emptySet()
                cachedHideDialog = prefs.getBoolean("hide_dialog", false)
                lastFetchTime = now
                fetched = true
                runCatching {
                    module.log(Log.INFO, TAG, "ConfigClient refreshed via remotePrefs: mode=$cachedMode, count=${cachedPackages.size}, pkgs=$cachedPackages, hideDialog=$cachedHideDialog")
                }
            }.onFailure {
                runCatching {
                    module.log(Log.WARN, TAG, "ConfigClient remotePrefs fetch failed: ${it.message}")
                }
            }
        }
    }

    fun isAllowed(context: Context?, packageName: String): Boolean {
        refresh(context)
        val inList = cachedPackages.contains(packageName)
        val allowed = when (cachedMode) {
            "whitelist" -> inList
            "blacklist" -> !inList
            else -> true
        }
        runCatching {
            module.log(Log.INFO, TAG, "isAllowed pkg=$packageName mode=$cachedMode inList=$inList -> $allowed")
        }
        return allowed
    }

    fun isHideDialog(context: Context?): Boolean {
        refresh(context)
        return cachedHideDialog
    }
}
