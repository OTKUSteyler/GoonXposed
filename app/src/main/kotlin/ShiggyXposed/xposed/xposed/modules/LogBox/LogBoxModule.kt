package GoonXposed.xposed.modules.LogBox

import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Companion.reloadApp
import GoonXposed.xposed.Utils.Log
import GoonXposed.xposed.hook
import android.app.Activity
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*

object LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var contextForMenu: Context? = null
    private val hookedMethods = mutableSetOf<String>()

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        // Enable React Native developer support so the shake gesture is detected.
        // MUST happen before the ReactHost is created (i.e. before the first Activity.onCreate),
        // otherwise useDeveloperSupport is read once and cached.
        enableDeveloperSupport(classLoader)

        return@with
    }

    private fun enableDeveloperSupport(classLoader: ClassLoader) {
        val hostCandidates = listOf(
            "com.discord.bridge.DCDReactNativeHost"
        )

        for (hostName in hostCandidates) {
            if (hookGetUseDeveloperSupport(classLoader, hostName)) {
                Log.e("Successfully hooked $hostName for dev support")
                return
            }
        }
        Log.e("Failed to hook any ReactNativeHost for dev support")
    }

    private fun hookGetUseDeveloperSupport(classLoader: ClassLoader, hostName: String): Boolean {
        val hostClass = try {
            XposedHelpers.findClassIfExists(hostName, classLoader)
        } catch (e: Throwable) {
            null
        } ?: return false

        val method = hostClass.declaredMethods.firstOrNull { it.name == "getUseDeveloperSupport" }
            ?: hostClass.methods.firstOrNull { it.name == "getUseDeveloperSupport" }
            ?: return false

        return try {
            method.isAccessible = true
            val signature = "${method.declaringClass.name}#${method.name}"
            if (hookedMethods.add(signature)) {
                method.hook {
                    before {
                        result = true
                    }
                }
            }
            true
        } catch (e: Throwable) {
            Log.e("Failed to hook getUseDeveloperSupport on $hostName: ${e.message}")
            false
        }
    }

    override fun onContext(context: Context) {
        try {
            Log.e("onContext called with context: $context")
            contextForMenu = context

            val possibleClasses = listOf(
                "com.facebook.react.devsupport.BridgeDevSupportManager",
                "com.facebook.react.devsupport.BridgelessDevSupportManager",
                "com.facebook.react.devsupport.DevSupportManagerImpl",
                "com.facebook.react.devsupport.DevSupportManagerBase",
                "com.facebook.react.devsupport.DefaultDevSupportManager",
                "com.facebook.react.devsupport.DevSupportManager"
            )

            var foundAny = false
            possibleClasses.forEach { className ->
                val clazz = try {
                    XposedHelpers.findClassIfExists(className, packageParam.classLoader)
                } catch (e: Throwable) {
                    null
                }
                if (clazz == null) {
                    Log.e("Class not found: $className")
                    return@forEach
                }
                Log.e("Found class: $className")
                hookDevSupportManager(clazz)
                foundAny = true
            }

            if (!foundAny) {
                Log.e("No DevSupport class was found - menu will not show on shake")
            }
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>) {
        Log.e("Attempting to hook ${clazz.name}")

        hookReloadJS(clazz)

        val showMethod = clazz.declaredMethods.firstOrNull { it.name == "showDevOptionsDialog" }
            ?: clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }
            ?: run {
                Log.e("showDevOptionsDialog not found on ${clazz.name}")
                return
            }

        val signature = "${showMethod.declaringClass.name}#${showMethod.name}"
        if (!hookedMethods.add(signature)) {
            Log.e("showDevOptionsDialog already hooked on ${showMethod.declaringClass.name}, skipping")
            return
        }

        try {
            XposedBridge.hookMethod(showMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val activityContext = getContextFromDevSupport(clazz, param.thisObject)
                            ?: contextForMenu
                        if (activityContext == null) {
                            // No usable context at all - let React's own dev options dialog
                            // run instead of showing nothing.
                            Log.e("No usable context for recovery menu - letting RN dialog show")
                            return
                        }

                        Log.e(
                            "Using context: $activityContext (type: ${activityContext.javaClass.name})"
                        )

                        // Only suppress React's dialog once ours is actually being shown.
                        // If showing the menu throws (e.g. bad Context), the exception is
                        // caught below and React's dialog still runs.
                        LogBoxNavigation.showRecoveryMenu(activityContext)
                        param.result = null
                    } catch (e: Throwable) {
                        Log.e("Failed to show recovery menu (falling back to RN dialog): ${e.message}")
                    }
                }
            })
            Log.e("Hooked showDevOptionsDialog on ${showMethod.declaringClass.name}")
        } catch (e: Throwable) {
            Log.e("Failed to hook showDevOptionsDialog on ${clazz.name}: ${e.message}")
        }
    }

    private fun hookReloadJS(clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull { it.name == "handleReloadJS" }
            ?: clazz.methods.firstOrNull { it.name == "handleReloadJS" }
            ?: return

        val signature = "${method.declaringClass.name}#${method.name}"
        if (!hookedMethods.add(signature)) return

        try {
            XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    Log.e("handleReloadJS called - reloading app")
                    reloadApp()
                    return null
                }
            })
            Log.e("Hooked handleReloadJS on ${method.declaringClass.name}")
        } catch (e: Throwable) {
            Log.e("Failed to hook handleReloadJS: ${e.message}")
        }
    }

    private fun getContextFromDevSupport(clazz: Class<*>, instance: Any?): Context? {
        if (instance == null) {
            Log.e("getContextFromDevSupport: instance is null")
            return null
        }

        try {
            // Helper objects that expose getCurrentActivity(). ReactInstanceDevHelper and
            // ReactHost are the ones guaranteed to hold the *Activity* (not the app context),
            // which is what an AlertDialog needs to actually appear on screen.
            val helpers = listOf(
                "mReactInstanceDevHelper",
                "reactInstanceDevHelper",
                "mReactHost",
                "reactHost",
                "mReactInstanceManager",
                "reactInstanceManager",
                "mBridge",
                "bridge",
                "mApplicationContext"
            )

            for (helperName in helpers) {
                try {
                    val helperField = XposedHelpers.findFieldIfExists(clazz, helperName)
                        ?: continue
                    val helper = helperField.get(instance) ?: continue

                    if (helper is Context) {
                        Log.e("Field $helperName is a Context, returning it")
                        return helper
                    }

                    // getCurrentActivity() exists on ReactInstanceDevHelper, ReactHost and
                    // ReactInstanceManager, and returns an actual Activity.
                    val getCurrentActivityMethod = helper.javaClass.methods.firstOrNull {
                        it.name == "getCurrentActivity"
                    }
                    if (getCurrentActivityMethod != null) {
                        getCurrentActivityMethod.isAccessible = true
                        val ctx = getCurrentActivityMethod.invoke(helper) as? Context
                        if (ctx != null) {
                            Log.e("Got activity context from $helperName.getCurrentActivity()")
                            return ctx
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Error trying $helperName: ${e.message}")
                }
            }

            // Direct Context fields on the DevSupportManager and its superclasses.
            try {
                var current = clazz
                while (current != null && current != Any::class.java) {
                    for (field in current.declaredFields) {
                        try {
                            field.isAccessible = true
                            val value = field.get(instance) ?: continue
                            if (value is Context) {
                                Log.e("Got context from field ${current.simpleName}.${field.name}")
                                return value
                            }
                        } catch (e: Exception) {
                            // skip fields we cannot read
                        }
                    }
                    current = current.superclass
                }
            } catch (e: Exception) {
                Log.e("Error scanning context fields: ${e.message}")
            }

            // Last resort: the currently resumed Activity from ActivityThread.
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThread =
                    activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
                mActivitiesField.isAccessible = true
                val activities = mActivitiesField.get(currentActivityThread)
                if (activities is Map<*, *>) {
                    for (record in activities.values) {
                        val activityField = record!!.javaClass.getDeclaredField("activity")
                        activityField.isAccessible = true
                        val activity = activityField.get(record) as? Activity
                        if (activity != null && !activity.isFinishing) {
                            Log.e("Got activity context from ActivityThread")
                            return activity
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ActivityThread lookup failed: ${e.message}")
            }

            Log.e("Could not get context from DevSupport object using any method")
        } catch (e: Exception) {
            Log.e("Failed to get context (outer catch): ${e.message}")
        }
        return null
    }
}
