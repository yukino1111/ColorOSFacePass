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
    private var hasLoadedValidConfig = false

    @Volatile
    var cachedMode = "whitelist"
        private set

    @Volatile
    var cachedPackages: Set<String> = emptySet()
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
                if (bundle != null && (bundle.containsKey("packages") || bundle.containsKey("mode"))) {
                    cachedMode = bundle.getString("mode", "whitelist") ?: "whitelist"
                    cachedPackages = bundle.getStringArrayList("packages")?.toSet() ?: emptySet()
                    lastFetchTime = now
                    hasLoadedValidConfig = true
                    fetched = true
                    runCatching {
                        module.log(Log.INFO, TAG, "ConfigClient refreshed via ContentProvider: mode=$cachedMode, count=${cachedPackages.size}")
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
                if (prefs.contains("packages") || prefs.contains("mode")) {
                    cachedMode = prefs.getString("mode", "whitelist") ?: "whitelist"
                    cachedPackages = prefs.getStringSet("packages", emptySet()) ?: emptySet()
                    lastFetchTime = now
                    hasLoadedValidConfig = true
                    fetched = true
                    runCatching {
                        module.log(Log.INFO, TAG, "ConfigClient refreshed via remotePrefs: mode=$cachedMode, count=${cachedPackages.size}, pkgs=$cachedPackages")
                    }
                } else {
                    runCatching {
                        module.log(Log.WARN, TAG, "ConfigClient remotePrefs returned uninitialized prefs, keeping current cache")
                    }
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
        // If configuration has never been loaded yet (e.g. immediately upon system boot before IPC settles),
        // fail-safe to allowed=true so ColorOSFaceFixHook does not block face recognition.
        if (!hasLoadedValidConfig && cachedPackages.isEmpty()) {
            runCatching {
                module.log(Log.INFO, TAG, "isAllowed pkg=$packageName fallback safe allow (no valid config loaded yet)")
            }
            return true
        }

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
}
