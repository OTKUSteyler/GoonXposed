package GoonXposed.xposed.modules.LogBox

import android.app.Activity
import android.app.AlertDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Based directly on Revenge's DiscordDevSupport.kt (the project GoonXposed is
 * built on top of). The earlier standalone SensorManager approach was a
 * workaround; THIS is how the actual working implementation does it.
 *
 * The real fix is two hooks:
 *   1. Force ReactNativeHost.getUseDeveloperSupport() (Discord's subclass:
 *      DCDReactNativeHost) to return true. This makes React Native construct
 *      the REAL BridgeDevSupportManager/BridgelessDevSupportManager instead
 *      of the release no-op one -- which means it registers a real
 *      ShakeDetector against SensorManager for you, for free.
 *   2. Hook showDevOptionsDialog on those manager classes to show your own
 *      menu instead of RN's stock dev menu, and swallow the original call.
 *
 * IMPORTANT CAVEAT (from Revenge's own comment, keep this in mind):
 * Enabling DevSupport doesn't just enable the shake menu -- it also exposes
 * other RN dev-support entry points, including the ability to point the app
 * at a Metro bundler server and load arbitrary JS from it. Revenge only
 * does this in DEBUG builds of their OWN Xposed module for that reason. If
 * you ship this in an always-on release build of GoonXposed, you're
 * knowingly accepting that tradeoff for every user of the module -- make
 * sure that's an intentional decision, not just a default you copy-pasted.
 */
object DiscordDevSupportHook {

    private const val RN_HOST_CLASS = "com.discord.bridge.DCDReactNativeHost"
    private val DEV_SUPPORT_MANAGER_CLASSES = listOf(
        "com.facebook.react.devsupport.BridgeDevSupportManager",
        "com.facebook.react.devsupport.BridgelessDevSupportManager",
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 1) Force developer support on at the ReactNativeHost level.
        val hostClass = findClassOrNull(RN_HOST_CLASS, classLoader)
        if (hostClass == null) {
            XposedBridge.log("GoonXposed: could not find $RN_HOST_CLASS -- class name may have changed in this Discord version")
        } else {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    hostClass,
                    "getUseDeveloperSupport",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
                XposedBridge.log("GoonXposed: forced getUseDeveloperSupport() = true")
            }.onFailure {
                XposedBridge.log("GoonXposed: failed to hook getUseDeveloperSupport: $it")
            }
        }

        // 2) Redirect the real showDevOptionsDialog (now actually reachable
        // via shake, since step 1 makes RN register the real ShakeDetector)
        // to your own menu instead of RN's stock dialog.
        for (className in DEV_SUPPORT_MANAGER_CLASSES) {
            val cls = findClassOrNull(className, classLoader) ?: continue

            runCatching {
                XposedHelpers.findAndHookMethod(
                    cls,
                    "showDevOptionsDialog",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = ActivityTracker.current
                            if (activity != null) {
                                activity.runOnUiThread { showRecoveryMenu(activity) }
                            } else {
                                XposedBridge.log("GoonXposed: showDevOptionsDialog fired but no Activity captured yet")
                            }
                            // Prevent RN's own dialog from also showing.
                            param.result = null
                        }
                    }
                )
                XposedBridge.log("GoonXposed: hooked showDevOptionsDialog on $className")
            }.onFailure {
                XposedBridge.log("GoonXposed: failed to hook showDevOptionsDialog on $className: $it")
            }
        }
    }

    private fun findClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
        try {
            XposedHelpers.findClass(name, classLoader)
        } catch (_: Throwable) {
            null
        }

    private fun showRecoveryMenu(activity: Activity) {
        // Replace with your actual Material 3 shake menu if it's already
        // built elsewhere -- this is a minimal placeholder so the wiring is
        // testable on its own.
        AlertDialog.Builder(activity)
            .setTitle("GoonXposed Recovery")
            .setItems(arrayOf("Reload", "Enter Recovery Mode")) { _, which ->
                when (which) {
                    0 -> { /* TODO: reload logic */ }
                    1 -> { /* TODO: recovery-mode logic */ }
                }
            }
            .show()
    }
}

/**
 * Minimal Activity tracker so DiscordDevSupportHook has a live Activity to
 * show its dialog on. Wire this into Activity.onResume the same way as the
 * earlier ShakeDetectorHook -- you likely already have this plumbing from
 * before, in which case just reuse that instead of adding a second one.
 */
object ActivityTracker {
    @Volatile
    var current: Activity? = null
        private set

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    current = param.thisObject as Activity
                }
            }
        )
    }
}
