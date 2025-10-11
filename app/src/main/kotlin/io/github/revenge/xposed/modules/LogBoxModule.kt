package ShiggyXposed.xposed.modules

import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Companion.reloadApp
import ShiggyXposed.xposed.Utils.Log
import android.app.AlertDialog
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    lateinit var bridgeDevSupportManagerClass: Class<*>

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) =
            with(packageParam) {
                this@LogBoxModule.packageParam = packageParam

                try {
                    val dcdReactNativeHostClass =
                            classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
                    bridgeDevSupportManagerClass =
                            classLoader.loadClass(
                                    "com.facebook.react.devsupport.BridgeDevSupportManager"
                            )

                    val getUseDeveloperSupportMethod =
                            dcdReactNativeHostClass.methods.first {
                                it.name == "getUseDeveloperSupport"
                            }
                    val handleReloadJSMethod =
                            bridgeDevSupportManagerClass.methods.first {
                                it.name == "handleReloadJS"
                            }
                    val showDevOptionsDialogMethod =
                            bridgeDevSupportManagerClass.methods.first {
                                it.name == "showDevOptionsDialog"
                            }
                    val mReactInstanceDevHelperField =
                            XposedHelpers.findField(
                                    bridgeDevSupportManagerClass,
                                    "mReactInstanceDevHelper"
                            )

                    // This enables the LogBox and opens dev option on shake
                    XposedBridge.hookMethod(
                            getUseDeveloperSupportMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = true
                                }
                            }
                    )

                    // Replace the method to direct relaunch the app instead of sending reload
                    // command to developer server
                    XposedBridge.hookMethod(
                            handleReloadJSMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    reloadApp()
                                    param.result = null
                                }
                            }
                    )

                    // Triggered on shake - Moved from onContext to onLoad
                    XposedBridge.hookMethod(
                            showDevOptionsDialogMethod,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val mReactInstanceDevHelper =
                                                mReactInstanceDevHelperField.get(param.thisObject)
                                        val getCurrentActivityMethod =
                                                mReactInstanceDevHelper.javaClass.methods.first {
                                                    it.name == "getCurrentActivity"
                                                }
                                        val context =
                                                getCurrentActivityMethod.invoke(
                                                        mReactInstanceDevHelper
                                                ) as
                                                        Context

                                        showRecoveryAlert(context)
                                    } catch (ex: Exception) {
                                        Log.e("Failed to show dev options dialog: $ex")
                                    }

                                    // Ignore the original dev menu
                                    param.result = null
                                }
                            }
                    )
                } catch (e: Exception) {
                    Log.e("Error in LogBoxModule.onLoad: $e")
                }

                return@with
            }

    private fun showRecoveryAlert(context: Context) {
        AlertDialog.Builder(context)
                .setTitle("ShiggyXposed Recovery Options")
                .setItems(arrayOf("Reload", "Delete bundle.js")) { _, which ->
                    when (which) {
                        0 -> {
                            reloadApp()
                        }
                        1 -> {
                            val bundleFile =
                                    File(
                                            packageParam.appInfo.dataDir,
                                            "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}"
                                    )
                            if (bundleFile.exists()) bundleFile.delete()

                            reloadApp()
                        }
                    }
                }
                .show()
    }
}
