package eu.rafareborn.biometricbypass.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONArray
import org.json.JSONObject

object ConfigManager {
    const val PREFS_NAME = "config"
    const val KEY_MODE = "mode"
    const val KEY_PACKAGES = "packages"
    const val KEY_HIDE_DIALOG = "hide_dialog"

    const val MODE_WHITELIST = "whitelist"
    const val MODE_BLACKLIST = "blacklist"

    private var xposedService: XposedService? = null
    private var isServiceInitialized = false

    fun init(context: Context) {
        if (isServiceInitialized) return
        isServiceInitialized = true

        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    xposedService = service
                    Log.i("BiometricBypass", "XposedService bound successfully")
                    // Sync local settings to remote service on bind
                    syncToRemote(context)
                }

                override fun onServiceDied(service: XposedService) {
                    xposedService = null
                    Log.w("BiometricBypass", "XposedService died")
                }
            })
        }.onFailure {
            Log.w("BiometricBypass", "Failed to register XposedService listener: ${it.message}")
        }
    }

    private fun getLocalPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): String =
        getLocalPrefs(context).getString(KEY_MODE, MODE_WHITELIST) ?: MODE_WHITELIST

    fun getPackages(context: Context): Set<String> =
        getLocalPrefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    fun isHideDialog(context: Context): Boolean =
        getLocalPrefs(context).getBoolean(KEY_HIDE_DIALOG, false)

    fun saveConfig(
        context: Context,
        mode: String,
        packages: Set<String>,
        hideDialog: Boolean,
    ) {
        val localPrefs = getLocalPrefs(context)
        localPrefs.edit()
            .putString(KEY_MODE, mode)
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean(KEY_HIDE_DIALOG, hideDialog)
            .apply()

        // Also write to Xposed remote preferences
        runCatching {
            xposedService?.let { service ->
                val remotePrefs = service.getRemotePreferences(PREFS_NAME)
                remotePrefs.edit()
                    .putString(KEY_MODE, mode)
                    .putStringSet(KEY_PACKAGES, packages)
                    .putBoolean(KEY_HIDE_DIALOG, hideDialog)
                    .commit()
            }
        }.onFailure {
            Log.w("BiometricBypass", "Error writing to remote preferences: ${it.message}")
        }
    }

    private fun syncToRemote(context: Context) {
        val mode = getMode(context)
        val packages = getPackages(context)
        val hideDialog = isHideDialog(context)
        runCatching {
            xposedService?.let { service ->
                val remotePrefs = service.getRemotePreferences(PREFS_NAME)
                remotePrefs.edit()
                    .putString(KEY_MODE, mode)
                    .putStringSet(KEY_PACKAGES, packages)
                    .putBoolean(KEY_HIDE_DIALOG, hideDialog)
                    .commit()
            }
        }
    }

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put(KEY_MODE, getMode(context))
        root.put(KEY_HIDE_DIALOG, isHideDialog(context))
        val array = JSONArray()
        getPackages(context).forEach { array.put(it) }
        root.put(KEY_PACKAGES, array)
        return root.toString(2)
    }

    fun importFromJson(context: Context, jsonStr: String): Boolean {
        return runCatching {
            val root = JSONObject(jsonStr)
            val mode = root.optString(KEY_MODE, MODE_WHITELIST)
            val hideDialog = root.optBoolean(KEY_HIDE_DIALOG, false)
            val jsonArray = root.optJSONArray(KEY_PACKAGES) ?: JSONArray()
            val packages = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                packages.add(jsonArray.getString(i))
            }
            saveConfig(context, mode, packages, hideDialog)
            true
        }.getOrDefault(false)
    }
}

