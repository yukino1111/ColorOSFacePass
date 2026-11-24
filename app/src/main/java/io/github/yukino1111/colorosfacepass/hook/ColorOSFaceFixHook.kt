package io.github.yukino1111.colorosfacepass.hook

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.util.Log
import io.github.yukino1111.colorosfacepass.data.ConfigClient
import io.github.yukino1111.colorosfacepass.module
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object ColorOSFaceFixHook {
    private const val TAG = "FaceBiometricFix"

    private const val OEM_STRENGTH_MASK = 4095
    private const val STRONG_AUTHENTICATORS = 15
    private const val WEAK_AUTHENTICATORS = 255
    private const val TYPE_FACE = 8
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val CODEBOOK_PACKAGE = "com.coloros.codebook"

    private val pendingOperationIds = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val currentOperationId = ThreadLocal<Long>()
    private val allowFaceUpgrade = ThreadLocal<Boolean>()

    @Volatile
    private var sensorModalityField: Field? = null

    fun hook(classLoader: ClassLoader) {
        hookAuthenticate(classLoader)
        hookPreAuthCreate(classLoader)
        hookPreAuthInfo(classLoader)
        hookUtils(classLoader)
        hookIsForeground(classLoader)
    }

    private fun hookAuthenticate(classLoader: ClassLoader) {
        runCatching {
            val promptInfoClass = Class.forName("android.hardware.biometrics.PromptInfo", false, classLoader)
            val wrapperClass =
                Class.forName(
                    "com.android.server.biometrics.BiometricService\$BiometricServiceWrapper",
                    false,
                    classLoader,
                )
            val method =
                wrapperClass.declaredMethods.firstOrNull {
                    it.name == "authenticate" &&
                        promptInfoClass in it.parameterTypes &&
                        Long::class.javaPrimitiveType in it.parameterTypes
                } ?: throw NoSuchMethodException("BiometricServiceWrapper#authenticate not found")
            method.isAccessible = true

            val types = method.parameterTypes
            val opIdIndex = types.indexOf(Long::class.javaPrimitiveType)
            val promptInfoIndex = types.indexOf(promptInfoClass)

            module.hook(method).intercept { chain ->
                val promptInfo = chain.getArg(promptInfoIndex)
                val opId = chain.getArg(opIdIndex) as? Long ?: 0L
                if (promptInfo != null) {
                    pendingOperationIds[promptInfo] = opId
                }
                chain.proceed()
            }
            module.log(Log.INFO, TAG, "hooked BiometricServiceWrapper#authenticate")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook BiometricServiceWrapper#authenticate", it)
        }
    }

    private fun hookPreAuthCreate(classLoader: ClassLoader) {
        runCatching {
            val promptInfoClass = Class.forName("android.hardware.biometrics.PromptInfo", false, classLoader)
            val preAuthInfoClass = Class.forName("com.android.server.biometrics.PreAuthInfo", false, classLoader)
            val createMethods =
                preAuthInfoClass.declaredMethods.filter {
                    it.name == "create" &&
                        !it.isSynthetic &&
                        Modifier.isStatic(it.modifiers) &&
                        promptInfoClass in it.parameterTypes
                }
            if (createMethods.isEmpty()) {
                throw NoSuchMethodException("PreAuthInfo.create(PromptInfo) not found")
            }

            createMethods.forEach { method ->
                method.isAccessible = true
                val promptInfoIndex = method.parameterTypes.indexOf(promptInfoClass)
                module.hook(method).intercept { chain ->
                    val promptInfo = chain.getArg(promptInfoIndex)
                    val opId = promptInfo?.let { pendingOperationIds.remove(it) } ?: 0L
                    currentOperationId.set(opId)
                    try {
                        chain.proceed()
                    } finally {
                        currentOperationId.remove()
                    }
                }
            }
            module.log(Log.INFO, TAG, "hooked PreAuthInfo#create count=${createMethods.size}")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook PreAuthInfo#create", it)
        }
    }

    private fun hookPreAuthInfo(classLoader: ClassLoader) {
        runCatching {
            val sensorClass = Class.forName("com.android.server.biometrics.BiometricSensor", false, classLoader)
            val preAuthInfoClass = Class.forName("com.android.server.biometrics.PreAuthInfo", false, classLoader)
            val method =
                preAuthInfoClass.declaredMethods.firstOrNull {
                    it.name == "getStatusForBiometricAuthenticator" && sensorClass in it.parameterTypes
                } ?: preAuthInfoClass.declaredMethods.firstOrNull {
                    it.name == "getStatusForBiometricAuthenticator"
                } ?: throw NoSuchMethodException("getStatusForBiometricAuthenticator not found")
            method.isAccessible = true

            val types = method.parameterTypes
            val sensorArgIndex = types.indexOf(sensorClass)
            val stringArgIndex = types.indexOfFirst { it == String::class.java }

            module.hook(method).intercept { chain ->
                val sensor = if (sensorArgIndex != -1) chain.getArg(sensorArgIndex) else null
                val packageName =
                    runCatching {
                        if (stringArgIndex != -1) {
                            chain.getArg(stringArgIndex) as? String
                        } else {
                            chain.args.filterIsInstance<String>().firstOrNull()
                        }
                    }.getOrNull()

                val modality = readSensorModality(sensor)
                val opId = currentOperationId.get() ?: 0L
                val shouldUpgrade =
                    evaluateUpgrade(
                        packageName = packageName,
                        modality = modality,
                        operationId = opId,
                    )

                if (shouldUpgrade) {
                    allowFaceUpgrade.set(true)
                }

                try {
                    chain.proceed()
                } finally {
                    allowFaceUpgrade.remove()
                }
            }
            module.log(Log.INFO, TAG, "hooked PreAuthInfo#getStatusForBiometricAuthenticator")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook PreAuthInfo", it)
        }
    }

    private fun evaluateUpgrade(
        packageName: String?,
        modality: Int?,
        operationId: Long,
    ): Boolean {
        if (packageName == null || packageName == SYSTEM_UI_PACKAGE || packageName == CODEBOOK_PACKAGE) {
            return false
        }
        if (modality != TYPE_FACE) {
            return false
        }
        if (operationId != 0L) {
            module.log(
                Log.INFO,
                TAG,
                "blocked 4095 upgrade for key-bound request pkg=$packageName operationId=$operationId",
            )
            return false
        }
        return if (isPackageAllowed(packageName)) {
            true
        } else {
            module.log(Log.INFO, TAG, "blocked 4095 upgrade by rule for pkg=$packageName")
            false
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
                val sensorStrength =
                    runCatching { chain.getArg(0) as? Int }.getOrNull()
                        ?: return@intercept chain.proceed()
                val requestedStrength =
                    runCatching { chain.getArg(1) as? Int }.getOrNull()
                        ?: return@intercept chain.proceed()

                if (allowFaceUpgrade.get() == true &&
                    sensorStrength == OEM_STRENGTH_MASK &&
                    (requestedStrength == STRONG_AUTHENTICATORS || requestedStrength == WEAK_AUTHENTICATORS)
                ) {
                    module.log(Log.INFO, TAG, "upgraded face 4095 to requestedStrength=$requestedStrength")
                    return@intercept true
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
            module.log(Log.ERROR, TAG, "failed to hook Utils#isAtLeastStrength", it)
        }
    }

    private fun hookIsForeground(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName("com.android.server.biometrics.Utils", false, classLoader)
            val method =
                utilsClass.declaredMethods.firstOrNull {
                    it.name == "isForeground" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: throw NoSuchMethodException("Utils#isForeground(int, int) not found")
            method.isAccessible = true

            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (result != false) {
                    return@intercept result
                }

                val callingUid = chain.getArg(0) as? Int ?: return@intercept false
                val context = getOrFindSystemContext() ?: return@intercept false
                val callerPackages =
                    runCatching {
                        context.packageManager
                            .getPackagesForUid(callingUid)
                            ?.toList()
                            .orEmpty()
                    }.getOrDefault(emptyList())
                val allowedPackage =
                    callerPackages.firstOrNull {
                        it != CODEBOOK_PACKAGE && ConfigClient.isAllowed(context, it)
                    } ?: return@intercept false

                val topPackage = readTopPackage(context)
                if (allowedPackage == topPackage) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "Utils#isForeground overridden to true for top pkg=$allowedPackage uid=$callingUid",
                    )
                    return@intercept true
                }
                false
            }
            module.log(Log.INFO, TAG, "hooked Utils#isForeground")
        }.onFailure {
            module.log(Log.ERROR, TAG, "failed to hook Utils#isForeground", it)
        }
    }

    private fun readSensorModality(sensor: Any?): Int? {
        if (sensor == null) return null
        val field =
            sensorModalityField ?: run {
                var current: Class<*>? = sensor.javaClass
                var found: Field? = null
                while (current != null && found == null) {
                    found = current.declaredFields.firstOrNull { it.name == "modality" }
                    current = current.superclass
                }
                found?.apply { isAccessible = true }?.also { sensorModalityField = it }
            }
        return runCatching { field?.getInt(sensor) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readTopPackage(context: Context): String? {
        val identity = Binder.clearCallingIdentity()
        return try {
            context
                .getSystemService(ActivityManager::class.java)
                ?.getRunningTasks(1)
                ?.firstOrNull()
                ?.topActivity
                ?.packageName
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(identity)
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
