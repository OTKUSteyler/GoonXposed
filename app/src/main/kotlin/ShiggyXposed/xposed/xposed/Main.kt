package GoonXposed.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.bridge.GoonBridge
import GoonXposed.xposed.api.HostScope
import GoonXposed.xposed.tweaks.*
import GoonXposed.xposed.tweaks.base.lifecycleSupport
import GoonXposed.xposed.tweaks.base.scriptLoader
import GoonXposed.xposed.tweaks.bridge.GoonBridgeRegistry
import GoonXposed.xposed.tweaks.bridge.additionalBridgeMethods
import GoonXposed.xposed.tweaks.bridge.goonBridgeSupport
import GoonXposed.xposed.tweaks.legacy.appearance.fonts
import GoonXposed.xposed.tweaks.legacy.appearance.sysColors
import GoonXposed.xposed.tweaks.legacy.appearance.themes
import GoonXposed.xposed.tweaks.legacy.goonPayloadGlobal
import GoonXposed.xposed.tweaks.discordVersionRetriever
import GoonXposed.xposed.tweaks.plugins.pluginLoader
import GoonXposed.xposed.tweaks.plugins.pluginStates
import GoonXposed.xposed.tweaks.plugins.repos.pluginRepos

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    private val tweaks: List<TweakSpec> = listOf(
        // Framework
        lifecycleSupport,
        goonBridgeSupport,
        scriptLoader,
        discordVersionRetriever,

        // Static patches
        fixResources,

        // Persistence
        caches,
        pluginStates,
        pluginRepos,

        // Async updater
        goonUpdater,

        // Consumers
        discordDevSupport,
        additionalBridgeMethods,
        fonts,
        themes,
        sysColors,
        pluginLoader,
        goonScriptLoader,
        goonPayloadGlobal,
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        // Only hook the main process.
        // Discord uses ProcessPhoenix to spawn a ":phoenix" process to restart the app. It will cause concurrency issues.
        if (param.processName != param.packageName) return

        if (hooked) return
        hooked = true

        val ctx = HostScopeImpl(
            modulePath = modulePath,
            appInfo = param.appInfo,
            classLoader = param.classLoader,
        )
        for (spec in tweaks) spec.applyTo(ctx)
    }
}

private class HostScopeImpl(
    override val modulePath: String,
    override val appInfo: ApplicationInfo,
    override val classLoader: ClassLoader,
) : HostScope {
    override val bridge: GoonBridge get() = GoonBridgeRegistry
    override fun withAppContext(block: (Context) -> Unit) =
        GoonXposed.xposed.tweaks.base.withAppContext(block)
    override fun withAppActivity(block: (Activity) -> Unit) =
        GoonXposed.xposed.tweaks.base.withAppActivity(block)
}
