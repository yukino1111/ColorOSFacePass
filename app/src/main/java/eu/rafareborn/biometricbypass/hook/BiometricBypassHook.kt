package eu.rafareborn.biometricbypass.hook

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.View
import eu.rafareborn.biometricbypass.TAG
import eu.rafareborn.biometricbypass.data.ConfigClient
import eu.rafareborn.biometricbypass.module
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object BiometricBypassHook {
    private const val TYPE_FACE = 8 // android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE

    private const val AUTH_CONTROLLER_CLASS = "com.android.systemui.biometrics.AuthController"
    private val TARGET_CLASSES =
        listOf(
            "com.android.systemui.biometrics.prompt.ui.AuthContainerView",
            "com.android.systemui.biometrics.AuthContainerView",
        )
    private const val TARGET_METHOD_ANIMATED_IN = "onDialogAnimatedIn"
    private const val CONFIG_FIELD = "mConfig"
    private const val OP_PACKAGE_FIELD = "mOpPackageName"
    private const val BUTTON_CONFIRM_ID = "button_confirm"

    private const val EVENT_MAX_WAIT_MS = 1_000L
    private const val DEFERRED_FALLBACK_TIMEOUT_MS = 600L

    private var configField: Field? = null
    private var opPackageNameField: Field? = null
    private var currentDialogField: Field? = null
    private var containerStateField: Field? = null

    private var activeContainerView: WeakReference<View>? = null
    private val animatedInViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val pendingConfirmViews = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val confirmedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var confirmButtonId: Int = 0

    fun hook(classLoader: ClassLoader) {
        hookAuthContainerView(classLoader)
        hookAuthController(classLoader)
    }

    private fun hookAuthContainerView(classLoader: ClassLoader) {
        val targetClass =
            TARGET_CLASSES.firstNotNullOfOrNull { name ->
                runCatching { classLoader.loadClass(name) }.getOrNull()
            } ?: run {
                module.log(Log.WARN, TAG, "AuthContainerView not found on known paths $TARGET_CLASSES")
                return
            }

        configField = targetClass.fieldByBaseName(CONFIG_FIELD)
        opPackageNameField = configField?.type?.fieldByBaseName(OP_PACKAGE_FIELD)
        containerStateField = targetClass.fieldByBaseName("mContainerState")

        // 1. Hook onDialogAnimatedIn for tracking & executing deferred confirmations
        val animInMethod = targetClass.zeroArgMethodByBaseName(TARGET_METHOD_ANIMATED_IN)
        if (animInMethod != null) {
            module.hook(animInMethod).intercept { chain ->
                val result = chain.proceed()

                val authContainerView = chain.thisObject as? View ?: return@intercept result
                activeContainerView = WeakReference(authContainerView)
                animatedInViews.add(authContainerView)

                // If face was recognized before the dialog finished animating in, execute deferred confirm now
                val pendingPkg = pendingConfirmViews.remove(authContainerView)
                if (pendingPkg != null) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "dialog animated in, executing deferred face confirm pkg=$pendingPkg",
                    )
                    authContainerView.post {
                        triggerFaceConfirm(authContainerView, pendingPkg)
                    }
                }

                result
            }
            module.log(Log.INFO, TAG, "hooked animated-in on ${targetClass.name}#${animInMethod.name}")
        }

        // 2. Fallback for older Android (10-14): hook onAuthenticationSucceeded(int modality) directly on AuthContainerView if present
        val authSucceedMethod =
            targetClass.declaredMethods.firstOrNull {
                it.name == "onAuthenticationSucceeded" &&
                    it.parameterCount >= 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        if (authSucceedMethod != null) {
            authSucceedMethod.isAccessible = true
            module.hook(authSucceedMethod).intercept { chain ->
                val modality = chain.getArg(0) as? Int ?: 0
                val result = chain.proceed()

                if (modality != TYPE_FACE) {
                    module.log(Log.DEBUG, TAG, "ignored non-face modality=$modality on AuthContainerView")
                    return@intercept result
                }

                val authContainerView = chain.thisObject as? View ?: return@intercept result
                val context = authContainerView.context
                val opPackageName = readOpPackageName(authContainerView)

                if (!ConfigClient.isAllowed(context, opPackageName)) {
                    module.log(Log.INFO, TAG, "skipped face confirm by rule for pkg=$opPackageName")
                    return@intercept result
                }

                scheduleFaceConfirm(authContainerView, opPackageName)
                result
            }
            module.log(Log.INFO, TAG, "hooked onAuthenticationSucceeded fallback on ${targetClass.name}")
        }
    }

    private fun hookAuthController(classLoader: ClassLoader) {
        val authControllerClass = runCatching { classLoader.loadClass(AUTH_CONTROLLER_CLASS) }.getOrNull()
        if (authControllerClass == null) {
            module.log(Log.WARN, TAG, "AuthController not found in classloader")
            return
        }

        currentDialogField = authControllerClass.fieldByBaseName("mCurrentDialog")

        // Hook onBiometricAuthenticated(int modality)
        val onBiometricAuthenticatedMethod =
            authControllerClass.declaredMethods.firstOrNull {
                it.name == "onBiometricAuthenticated" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }

        if (onBiometricAuthenticatedMethod != null) {
            onBiometricAuthenticatedMethod.isAccessible = true
            module.hook(onBiometricAuthenticatedMethod).intercept { chain ->
                val modality = chain.getArg(0) as? Int ?: 0
                val result = chain.proceed()

                // 严格限定认证模态：只有人脸认证成功 (TYPE_FACE == 8) 才触发自动继续
                if (modality != TYPE_FACE) {
                    module.log(Log.DEBUG, TAG, "ignored non-face modality=$modality on AuthController")
                    return@intercept result
                }

                val currentDialog =
                    (currentDialogField?.get(chain.thisObject) as? View)
                        ?: activeContainerView?.get()

                if (currentDialog == null) {
                    module.log(Log.WARN, TAG, "face authenticated but current dialog is null")
                    return@intercept result
                }

                val context = currentDialog.context
                val opPackageName = readOpPackageName(currentDialog)

                if (!ConfigClient.isAllowed(context, opPackageName)) {
                    module.log(Log.INFO, TAG, "skipped face confirm by rule for pkg=$opPackageName")
                    return@intercept result
                }

                // 调度人脸确认：若入场动画尚未完成，则延迟至入场后确认，避免底层触发 USER_CANCELED 状态竞态
                scheduleFaceConfirm(currentDialog, opPackageName)
                result
            }
            module.log(Log.INFO, TAG, "hooked onBiometricAuthenticated on ${authControllerClass.name}")
        } else {
            module.log(Log.WARN, TAG, "onBiometricAuthenticated not found on ${authControllerClass.name}")
        }
    }

    private fun scheduleFaceConfirm(
        authContainerView: View,
        opPackageName: String,
    ) {
        if (confirmedViews.contains(authContainerView)) {
            return
        }

        val isAnimatedIn = animatedInViews.contains(authContainerView) || isContainerStateReady(authContainerView)
        if (isAnimatedIn) {
            module.log(Log.INFO, TAG, "face authenticated after dialog ready, confirming pkg=$opPackageName")
            authContainerView.post {
                triggerFaceConfirm(authContainerView, opPackageName)
            }
        } else {
            module.log(
                Log.INFO,
                TAG,
                "face authenticated before dialog animated in, deferring confirm pkg=$opPackageName",
            )
            pendingConfirmViews[authContainerView] = opPackageName

            // Fallback timeout to prevent permanent hang if onDialogAnimatedIn is skipped on certain vendor ROMs
            authContainerView.postDelayed({
                val pendingPkg = pendingConfirmViews.remove(authContainerView)
                if (pendingPkg != null && !confirmedViews.contains(authContainerView)) {
                    module.log(Log.WARN, TAG, "fallback confirm triggered after timeout for pkg=$pendingPkg")
                    triggerFaceConfirm(authContainerView, pendingPkg)
                }
            }, DEFERRED_FALLBACK_TIMEOUT_MS)
        }
    }

    private fun isContainerStateReady(authContainerView: View): Boolean {
        return runCatching {
            val state = containerStateField?.getInt(authContainerView) ?: return@runCatching false
            // STATE_ANIMATING_IN is typically 1; states >= 3 represent fully shown/ready states
            state >= 3
        }.getOrDefault(false)
    }

    private fun triggerFaceConfirm(
        authContainerView: View,
        opPackageName: String,
    ) {
        if (confirmedViews.contains(authContainerView)) {
            return
        }
        confirmedViews.add(authContainerView)

        val context = authContainerView.context
        if (confirmButtonId == 0) {
            @SuppressLint("DiscouragedApi")
            val id = context.resources.getIdentifier(BUTTON_CONFIRM_ID, "id", context.packageName)
            confirmButtonId = id
        }
        val buttonId = confirmButtonId

        if (buttonId == 0) {
            module.log(Log.WARN, TAG, "confirm button missing id pkg=$opPackageName")
            return
        }

        val startUptimeMs = SystemClock.uptimeMillis()
        if (clickConfirm(authContainerView, buttonId, opPackageName, startUptimeMs)) {
            return
        }

        retryUntilShown(authContainerView, buttonId, opPackageName, startUptimeMs)
    }

    private fun Class<*>.zeroArgMethodByBaseName(base: String): Method? {
        val zeroArg = declaredMethods.filter { it.parameterCount == 0 }
        return zeroArg.firstOrNull { it.name == base }
            ?: zeroArg.firstOrNull { it.name.startsWith("$base\$") }
    }

    private fun Class<*>.fieldByBaseName(base: String): Field? {
        val field =
            declaredFields.firstOrNull { it.name == base }
                ?: declaredFields.firstOrNull { it.name.startsWith("$base\$") }
        return field?.apply { isAccessible = true }
    }

    private fun readOpPackageName(authContainerView: View): String {
        // Try getOpPackageName()
        runCatching {
            val m =
                authContainerView.javaClass.methods.firstOrNull {
                    it.name == "getOpPackageName" && it.parameterCount == 0
                }
            (m?.invoke(authContainerView) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }

        // Fallback to mConfig.mOpPackageName
        return runCatching {
            val config = configField?.get(authContainerView) ?: return@runCatching null
            opPackageNameField?.get(config) as? String
        }.getOrNull() ?: "unknown"
    }

    private fun clickConfirm(
        parentView: View,
        buttonId: Int,
        opPackageName: String,
        startUptimeMs: Long,
    ): Boolean {
        val button = parentView.findViewById<View?>(buttonId)
        if (button == null || !button.isShown || !button.isEnabled) return false
        button.performClick()
        val latencyMs = SystemClock.uptimeMillis() - startUptimeMs
        module.log(Log.INFO, TAG, "face confirm clicked pkg=$opPackageName latencyMs=$latencyMs")
        return true
    }

    private fun retryUntilShown(
        parentView: View,
        buttonId: Int,
        opPackageName: String,
        startUptimeMs: Long,
    ) {
        parentView.postOnAnimation {
            if (clickConfirm(parentView, buttonId, opPackageName, startUptimeMs)) {
                return@postOnAnimation
            }
            val elapsedMs = SystemClock.uptimeMillis() - startUptimeMs
            if (elapsedMs >= EVENT_MAX_WAIT_MS) {
                module.log(Log.WARN, TAG, "face confirm button not found pkg=$opPackageName waitMs=$elapsedMs")
                return@postOnAnimation
            }
            retryUntilShown(parentView, buttonId, opPackageName, startUptimeMs)
        }
    }
}
