package GoonXposed.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.File
import java.lang.reflect.Method

/**
 * Base class for every Xposed side module.
 *
 * The [Main] loader forwards the various [XC_LoadPackage] lifecycle callbacks to every
 * registered [Module] instance. Modules may override any subset of the hooks below.
 */
open class Module {
    open fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {}

    open fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {}

    open fun onContext(context: Context) {}

    open fun onActivity(activity: Activity) {}

    @Deprecated("This method is deprecated in the parent class")
    open fun buildPayload(builder: JsonObjectBuilder) {}
}

/**
 * Runtime wrapper around an [XC_MethodHook.MethodHookParam] used as the receiver of every
 * `before`/`after` hook block, exposing [thisObject], [args], [result] and [param] directly.
 */
class HookScope(val param: XC_MethodHook.MethodHookParam) {
    val thisObject: Any?
        get() = param.thisObject

    val args: Array<Any?>
        get() = param.args

    var result: Any?
        get() = param.result
        set(value) {
            param.result = value
        }

    var throwable: Throwable?
        get() = param.throwable
        set(value) {
            param.throwable = value
        }
}

/**
 * DSL builder for lazily constructed [XC_MethodHook] instances.
 *
 * ```kotlin
 * val hook = MethodHookBuilder().run {
 *     before { result = null }
 *     after { Log.i("done") }
 *     build()
 * }
 * ```
 */
class MethodHookBuilder {
    private val beforeCallbacks = mutableListOf<HookScope.() -> Unit>()
    private val afterCallbacks = mutableListOf<HookScope.() -> Unit>()

    fun before(callback: HookScope.() -> Unit) {
        beforeCallbacks += callback
    }

    fun after(callback: HookScope.() -> Unit) {
        afterCallbacks += callback
    }

    fun build(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val scope = HookScope(param)
            beforeCallbacks.forEach { scope.run(it) }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val scope = HookScope(param)
            afterCallbacks.forEach { scope.run(it) }
        }
    }

    companion object {
        fun from(block: MethodHookBuilder.() -> Unit): XC_MethodHook = MethodHookBuilder().run {
            block()
            build()
        }
    }
}

fun ClassLoader.safeLoadClass(name: String): Class<*>? = try {
    loadClass(name)
} catch (e: Throwable) {
    null
}

fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method =
    XposedHelpers.findMethodExact(this, name, *parameterTypes)

fun Method.hook(block: MethodHookBuilder.() -> Unit) {
    val builder = MethodHookBuilder()
    builder.block()
    XposedBridge.hookMethod(this, builder.build())
}

fun Method.hook(hook: XC_MethodHook) {
    XposedBridge.hookMethod(this, hook)
}

fun Class<*>.hookMethod(
    name: String,
    vararg parameterTypes: Class<*>,
    block: MethodHookBuilder.() -> Unit
): Method {
    val method = method(name, *parameterTypes)
    method.hook(block)
    return method
}

fun File.asDir(): File {
    if (!exists() || !isDirectory) mkdirs()
    return this
}

fun File.asFile(): File {
    parentFile?.mkdirs()
    return this
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String,
    val versionCode: Int
)

fun Context.getAppInfo(): AppInfo {
    val label = applicationInfo.loadLabel(packageManager).toString()
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    return AppInfo(
        name = label,
        packageName = packageName,
        version = packageInfo?.versionName ?: "",
        versionCode = packageInfo?.versionCode ?: 0
    )
}