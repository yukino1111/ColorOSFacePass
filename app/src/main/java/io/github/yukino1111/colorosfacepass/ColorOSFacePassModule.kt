package io.github.yukino1111.colorosfacepass

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.yukino1111.colorosfacepass.data.ConfigClient
import io.github.yukino1111.colorosfacepass.hook.BiometricBypassHook
import io.github.yukino1111.colorosfacepass.hook.ColorOSFaceFixHook

internal const val TAG = "ColorOSFacePass"
private const val TARGET_PACKAGE = "com.android.systemui"

@PublishedApi
internal lateinit var module: ColorOSFacePassModule

class ColorOSFacePassModule : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        processName = param.processName
        log(Log.INFO, TAG, "loaded version=${BuildConfig.VERSION_NAME} proc=$processName")
        ConfigClient.refresh(null)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        module = this
        log(Log.INFO, TAG, "hooking biometrics in system_server (ColorOS Face Fix)...")
        ConfigClient.refresh(null)
        try {
            ColorOSFaceFixHook.hook(param.classLoader)
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "failed to install ColorOSFaceFixHook", e)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // only the main process hosts the auth dialog
        if (processName != TARGET_PACKAGE || !param.isFirstPackage) return

        try {
            BiometricBypassHook.hook(param.classLoader)
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "hook failed pkg=$TARGET_PACKAGE", e)
        }
    }
}
