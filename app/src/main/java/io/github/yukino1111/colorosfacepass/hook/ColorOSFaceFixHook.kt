package io.github.yukino1111.colorosfacepass.hook

import android.content.Context
import android.util.Log
import io.github.yukino1111.colorosfacepass.data.ConfigClient
import io.github.yukino1111.colorosfacepass.module
import java.lang.reflect.Method

object ColorOSFaceFixHook {
    private const val TAG = "FaceBiometricFix"

    private const val OEM_STRENGTH_MASK = 4095
    private const val STRONG_AUTHENTICATORS = 15
    private const val WEAK_AUTHENTICATORS = 255
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val CODEBOOK_PACKAGE = "com.coloros.codebook"

    private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

    fun hook(classLoader: ClassLoader) {
        hookPreAuthInfo(classLoader)
        hookUtils(classLoader)
    }

    private fun hookPreAuthInfo(classLoader: ClassLoader) {
        runCatching {
            val preAuthInfoClass = Class.forName("com.android.server.biometrics.PreAuthInfo", false, classLoader)
            val method =
                preAuthInfoClass.declaredMethods.firstOrNull {
                    it.name == "getStatusForBiometricAuthenticator"
                } ?: throw NoSuchMethodException("getStatusForBiometricAuthenticator not found")
            method.isAccessible = true

            // Locate which argument index is String (opPackageName)
            val stringArgIndex = method.parameterTypes.indexOfFirst { it == String::class.java }

            module.hook(method).intercept { chain ->
                val packageName =
                    runCatching {
                        if (stringArgIndex != -1) {
                            chain.getArg(stringArgIndex) as? String
                        } else {
                            chain.args.filterIsInstance<String>().firstOrNull()
                        }
                    }.getOrNull()

                if (packageName != null && packageName != SYSTEM_UI_PACKAGE) {
                    APP_PRE_AUTH_PACKAGE.set(packageName)
                }

                try {
                    chain.proceed()
                } finally {
                    APP_PRE_AUTH_PACKAGE.remove()
                }
            }
            module.log(Log.INFO, TAG, "hooked PreAuthInfo#getStatusForBiometricAuthenticator")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook PreAuthInfo", it)
        }
    }

    private fun hookUtils(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName("com.android.server.biometrics.Utils", false, classLoader)
            val method =
                utilsClass.declaredMethods.firstOrNull {
                    it.name == "isAtLeastStrength" && it.parameterCount == 2
                } ?: throw NoSuchMethodException("Utils#isAtLeastStrength(int, int) not found")
            method.isAccessible = true

            module.hook(method).intercept { chain ->
                val packageName = APP_PRE_AUTH_PACKAGE.get()
                val sensorStrength =
                    runCatching { chain.getArg(0) as? Int }.getOrNull()
                        ?: return@intercept chain.proceed()
                val requestedStrength =
                    runCatching { chain.getArg(1) as? Int }.getOrNull()
                        ?: return@intercept chain.proceed()

                if (packageName != null &&
                    packageName != CODEBOOK_PACKAGE &&
                    sensorStrength == OEM_STRENGTH_MASK &&
                    (requestedStrength == STRONG_AUTHENTICATORS || requestedStrength == WEAK_AUTHENTICATORS)
                ) {
                    if (isPackageAllowed(packageName)) {
                        module.log(Log.INFO, TAG, "upgraded 4095 to Class 3 for pkg=$packageName")
                        return@intercept true
                    } else {
                        module.log(Log.INFO, TAG, "blocked 4095 upgrade by rule for pkg=$packageName")
                    }
                }

                // Keep exact ColorOS native implementation for fallback
                val maskedStrength = sensorStrength and 32767
                if (((requestedStrength.inv()) and maskedStrength) != 0) return@intercept false
                var candidate = 1
                while (candidate <= requestedStrength) {
                    if (candidate == maskedStrength) return@intercept true
                    candidate = (candidate shl 1) or 1
                }
                false
            }
            module.log(Log.INFO, TAG, "hooked Utils#isAtLeastStrength")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook Utils", it)
        }
    }

    private fun getOrFindSystemContext(): Context? =
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            atClass.getDeclaredMethod("getSystemContext").invoke(currentAt) as? Context
        }.getOrNull()

    private fun isPackageAllowed(packageName: String): Boolean {
        val context = getOrFindSystemContext()
        return ConfigClient.isAllowed(context, packageName)
    }
}
