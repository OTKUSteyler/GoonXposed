package GoonXposed.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.Utils.Log
import GoonXposed.xposed.modules.*
import GoonXposed.xposed.modules.appearance.FontsModule
import GoonXposed.xposed.modules.appearance.SysColorsModule
import GoonXposed.xposed.modules.appearance.ThemesModule
import GoonXposed.xposed.modules.bridge.AdditionalBridgeMethodsModule
import GoonXposed.xposed.modules.bridge.BridgeModule
import GoonXposed.xposed.modules.no_track.BlockCrashReportingModule
import GoonXposed.xposed.modules.no_track.BlockDeepLinksTrackingModule
import GoonXposed.xposed.modules.LogBox.*
import kotlinx.coroutines.CompletableDeferred

object HookStateHolder {
    /**
     * Whether all hooks are completed, and we are ready to load the JS bundle.
     */
    val readyDeferred = CompletableDeferred<Unit>()

    /**
     * Whether we have successfully received a [Context] yet.
     * Sometimes the app process is recreated and Xposed hooks way too late for us to get [Context] from [ContextWrapper.attachBaseContext].
     * But since Xposed hooks before [Activity.onCreate], we can still get it from there and still initialize properly.
     */
    @Volatile
    var gotContext = false
}

class Main : Module(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var hooked = false
    private val modules = mutableListOf(
        HookScriptLoaderModule,
        BridgeModule,
        AdditionalBridgeMethodsModule,
        PluginsModule(),
        UpdaterModule,
        FixResourcesModule,
        BlockDeepLinksTrackingModule,
        BlockCrashReportingModule,
        LogBoxModule,
        CacheModule,
        PerfPatchesModule,
        FontsModule,
        ThemesModule,
        SysColorsModule
    )

    init {
        modules += PayloadGlobalModule(modules)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        dispatch("onInit") { module -> module.onInit(startupParam) }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        return pkg == Constants.TARGET_PACKAGE
                || pkg.startsWith("com.discord")
                || pkg == "dev.shiggy.cord"
                || pkg.contains("discord")
                || pkg.contains("gooncord")
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        if (!isTargetPackage(packageName)) return
        if (processName != packageName) return
        if (hooked) return

        val activityClassNames = listOf(
            Constants.TARGET_ACTIVITY,
            "$packageName.react_activities.ReactActivity",
            "com.discord.react_activities.ReactActivity",
            "com.facebook.react.ReactActivity"
        )
        var reactActivity: Class<*>? = null
        for (className in activityClassNames) {
            try {
                reactActivity = classLoader.loadClass(className)
                if (reactActivity != null) break
            } catch (_: Throwable) {}
        }

        if (reactActivity == null) {
            Log.w("Target activity not found by name, falling back to Activity base class")
        }

        ContextWrapper::class.java.hookMethod("attachBaseContext", Context::class.java) {
            after {
                val ctx = args[0] as Context
                HookStateHolder.gotContext = true
                Log.i("Received Context")
                this@Main.onContext(ctx)
            }
        }

        val targetActivityClass = reactActivity ?: Activity::class.java
        targetActivityClass.hookMethod("onCreate", Bundle::class.java) {
            after {
                val act = thisObject as Activity
                if (reactActivity == null &&
                    !act.javaClass.name.contains("ReactActivity", ignoreCase = true) &&
                    !act.javaClass.name.contains("MainActivity", ignoreCase = true)
                ) {
                    return@after
                }
                Log.i("Received Activity: ${act.javaClass.name}")

                if (!HookStateHolder.gotContext) {
                    Log.w("Activity created before we got Context, process may have been recreated!")
                    this@Main.onContext(act.applicationContext)
                }

                this@Main.onActivity(act)
                HookStateHolder.readyDeferred.complete(Unit)
            }
        }

        targetActivityClass.hookMethod("onResume") {
            after {
                val act = thisObject as Activity
                this@Main.onResume(act)
            }
        }

        targetActivityClass.hookMethod("onPause") {
            after {
                val act = thisObject as Activity
                this@Main.onPause(act)
            }
        }

        this@Main.onLoad(param)

        hooked = true
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        dispatch("onLoad") { module -> module.onLoad(packageParam) }
    }

    override fun onContext(context: Context) {
        dispatch("onContext") { module -> module.onContext(context) }
    }

    override fun onActivity(activity: Activity) {
        dispatch("onActivity") { module -> module.onActivity(activity) }
    }

    override fun onResume(activity: Activity) {
        dispatch("onResume") { module -> module.onResume(activity) }
    }

    override fun onPause(activity: Activity) {
        dispatch("onPause") { module -> module.onPause(activity) }
    }

    private fun dispatch(stage: String, block: (Module) -> Unit) {
        for (module in modules) {
            val name = module.javaClass.simpleName.ifEmpty { module.javaClass.name }
            try {
                block(module)
            } catch (e: Throwable) {
                Log.e("Module $name failed during $stage", e)
            }
        }
    }
}
