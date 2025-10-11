package ShiggyXposed.xposed

import ShiggyXposed.xposed.modules.*
import ShiggyXposed.xposed.modules.appearance.FontsModule
import ShiggyXposed.xposed.modules.appearance.SysColorsModule
import ShiggyXposed.xposed.modules.appearance.ThemesModule
import ShiggyXposed.xposed.modules.bridge.AdditionalBridgeMethodsModule
import ShiggyXposed.xposed.modules.bridge.BridgeModule
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Main : Module(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var hooked = false
    private val modules =
            mutableListOf(
                    HookScriptLoaderModule(),
                    BridgeModule(),
                    AdditionalBridgeMethodsModule(),
                    UpdaterModule(),
                    FixResourcesModule(),
                    LogBoxModule(),
                    FontsModule(),
                    ThemesModule(),
                    SysColorsModule()
            )

    init {
        modules.add(PayloadGlobalModule(modules))
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        for (module in modules) module.onInit(startupParam)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) =
            with(param) {
                if (hooked) return

                val reactActivity = classLoader.loadClass(Constants.TARGET_ACTIVITY)

                ContextWrapper::class.java.hookMethod("attachBaseContext", Context::class.java) {
                    after { this@Main.onContext(args[0] as Context) }
                }

                reactActivity.hookMethod("onCreate", Bundle::class.java) {
                    after { this@Main.onActivity(thisObject as Activity) }
                }

                this@Main.onLoad(param)

                hooked = true
            }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        for (module in modules) module.onLoad(packageParam)
    }

    override fun onContext(context: Context) {
        for (module in modules) module.onContext(context)
    }

    override fun onActivity(activity: Activity) {
        for (module in modules) module.onActivity(activity)
    }
}
