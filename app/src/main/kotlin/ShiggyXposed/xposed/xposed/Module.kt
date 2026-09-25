package GoonXposed.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.File
import java.lang.reflect.Method

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
)

/**
 * Base class for every Xposed side module.
 *
 * The [Main] loader forwards the various [XC_LoadPackage] lifecycle callbacks to every
 * registered [Module] instance. Modules may override any subset of the hooks below.
 */
open class Module {
    /**
     * Builds a JSON payload to be injected into the JavaScript context.
     */
    @Deprecated("This will be removed in future versions. Payloads can be replaced via synchronous bridge methods.")
    open fun buildPayload(builder: JsonObjectBuilder) {}

    /**
     * Called during Zygote initialization.
     */
    open fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {}

    /**
     * Called when a package is loaded.
     *
     * Hooks should typically be set up here.
     *
     * Bridge methods that do not require [Context] can also be registered here.
     */
    open fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {}

    /**
     * Called after [Context] has been attached to a [android.content.ContextWrapper]
     * via [android.content.ContextWrapper.attachBaseContext].
     *
     * This may be called multiple times for different [Context]s, e.g. when the app is killed by the system.
     *
     * For bridge methods that require access to [Context], register them here.
     */
    open fun onContext(context: Context) {}

    /**
     * Called after an [Activity.onCreate] method is executed.
     *
     * UI code that requires an [Activity] context can be placed here.
     *
     * Bridge methods must not be registered here, as it can be too late and cause crashes.
     *
     * If you need to register bridge methods that require an [Activity], do it in [onContext] and make it async.
     *
     * Once an [Activity] is available, execute the queued calls. Then from JS, call the method asynchronously.
     */
    open fun onActivity(activity: Activity) {}

    open fun onResume(activity: Activity) {}

    open fun onPause(activity: Activity) {}

    protected fun Context.getAppInfo(): AppInfo = (this as Context).getAppInfo()
    protected fun File.asDir(): File = (this as File).asDir()
    protected fun File.asFile(): File = (this as File).asFile()
    protected fun ClassLoader.safeLoadClass(name: String): Class<*>? = (this as ClassLoader).safeLoadClass(name)
    protected fun Class<*>.method(name: String, vararg parameterTypes: Class<*>?): Method = (this as Class<*>).method(name, *parameterTypes)
    protected fun Method.hook(hook: XC_MethodHook): XC_MethodHook.Unhook = (this as Method).hook(hook)
    protected fun Method.hook(block: MethodHookBuilder.() -> Unit): XC_MethodHook.Unhook = (this as Method).hook(block)
    protected fun Class<*>.hookMethod(
        name: String,
        vararg parameterTypes: Class<*>?,
        block: MethodHookBuilder.() -> Unit
    ): XC_MethodHook.Unhook = (this as Class<*>).hookMethod(name, *parameterTypes, block = block)
}

/**
 * Runtime wrapper around an [XC_MethodHook.MethodHookParam] used as the receiver of every
 * `before`/`after` hook block, exposing [thisObject], [args], [result] and [param] directly.
 */
class HookScope(
    val param: XC_MethodHook.MethodHookParam,
    private val proceed: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
) {
    fun proceed() {
        proceed?.invoke(param)
    }

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
            val scope = HookScope(param = param, proceed = { p -> super.beforeHookedMethod(p) })
            beforeCallbacks.forEach { scope.run(it) }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val scope = HookScope(param = param, proceed = { p -> super.afterHookedMethod(p) })
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

fun Class<*>.method(name: String, vararg parameterTypes: Class<*>?): Method =
    XposedHelpers.findMethodExact(this, name, *parameterTypes)

fun Method.hook(hook: XC_MethodHook): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, hook)

fun Method.hook(block: MethodHookBuilder.() -> Unit): XC_MethodHook.Unhook {
    val builder = MethodHookBuilder()
    builder.block()
    return XposedBridge.hookMethod(this, builder.build())
}

fun Class<*>.hookMethod(
    name: String,
    vararg parameterTypes: Class<*>?,
    block: MethodHookBuilder.() -> Unit
): XC_MethodHook.Unhook {
    val method = method(name, *parameterTypes)
    return method.hook(block)
}

fun File.asDir(): File {
    if (!exists() || !isDirectory) mkdirs()
    return this
}

fun File.asFile(): File {
    parentFile?.mkdirs()
    return this
}

fun Context.getAppInfo(): AppInfo {
    val label = applicationInfo.loadLabel(packageManager).toString()
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    val versionCode = if (packageInfo != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode
        else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
    } else 0L

    return AppInfo(
        name = label,
        packageName = packageName,
        version = packageInfo?.versionName ?: "",
        versionCode = versionCode
    )
}
