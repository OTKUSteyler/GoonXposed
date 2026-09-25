package GoonXposed.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import GoonXposed.xposed.Constants
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Log
import java.io.File

/**
 * PerfPatchesModule
 *
 * Writes a small JavaScript preload into the module's `preloads` directory.
 * The preload performs guarded, best-effort runtime monkeypatches to:
 *  - add memoization to expensive pure functions (if present)
 *  - cache RowGenerator.generate outputs on instances when inputs look stable
 *  - batch analytics/log events to avoid high-frequency synchronous work
 *
 * The implementation is intentionally defensive: it never throws and only applies
 * patches if the targeted symbols exist.
 */
object PerfPatchesModule : Module() {
    private const val FILE_NAME = "perf_patches.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        try {
            val filesDir = File(packageParam.appInfo.dataDir, Constants.FILES_DIR)
            val preloadsDir = File(filesDir, HookScriptLoaderModule.PRELOADS_DIR)
            val out = File(preloadsDir, FILE_NAME)
            if (out.exists()) {
                out.delete()
                Log.i("PerfPatchesModule: deleted legacy perf_patches.js")
            }
        } catch (e: Throwable) {
            Log.e("PerfPatchesModule: cleanup failed", e)
        }
    }

    @Deprecated("Overrides deprecated Module.buildPayload")
    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.put("perfPatches", false)
    }
}
