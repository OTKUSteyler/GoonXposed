@file:Suppress("DEPRECATION")

package GoonXposed.xposed.modules.appearance

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.Constants
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Companion.JSON
import GoonXposed.xposed.Utils.Log
import GoonXposed.xposed.asDir
import GoonXposed.xposed.asFile
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

@Serializable
data class FontDefinition(
    val name: String? = null,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>,
)

object FontsModule : Module() {
    private val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    private val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    private const val FONTS_ASSET_PATH = "fonts/"

    private lateinit var fontsDir: File
    private var fontsDownloadsDir: File? = null
    private var fontsAbsPath: String? = null

    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.put("fontPatch", 2)
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        // ReactFontManager hijack runs regardless of fonts.json presence; it falls back to the
        // default Typeface chain if no custom font file is found.
        listOf(
            "com.facebook.react.common.assets.ReactFontManager\$Companion",
            "com.facebook.react.views.text.ReactFontManager\$Companion",
        ).forEach { clsName ->
            XposedHelpers.findClassIfExists(clsName, classLoader)?.let { cls ->
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        cls,
                        "createAssetTypeface",
                        String::class.java,
                        Int::class.java,
                        AssetManager::class.java,
                        object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                val fontFamilyName: String = param.args[0].toString()
                                val style: Int = param.args[1] as Int
                                val assetManager: AssetManager = param.args[2] as AssetManager
                                return createAssetTypeface(fontFamilyName, style, assetManager)
                            }
                        },
                    )
                }.onFailure { e ->
                    Log.e("Failed to hook ReactFontManager: ${e.message}")
                }
            }
        }

        val fontDefFile = File(appInfo.dataDir, "${Constants.FILES_DIR}/fonts.json")
        if (!fontDefFile.exists()) return@with

        val fontDef = try {
            JSON.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (e: Throwable) {
            Log.w("fonts.json malformed: ${e.message}")
            return@with
        }
        val setName = fontDef.name ?: return@with

        val downloadsDir = File(appInfo.dataDir, "${Constants.FILES_DIR}/downloads/fonts").asDir()
        fontsDir = File(downloadsDir, setName).asDir()
        fontsDownloadsDir = downloadsDir
        fontsAbsPath = fontsDir.absolutePath + "/"

        // Prune stale font files for this set.
        fontsDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    Log.i("Deleting stale font file: $fileName")
                    file.delete()
                }
            }
        }

        // Font files are normally downloaded by the JS side; this is a safety net for
        // font packs that only ship a fonts.json pointing at remote URLs.
        CoroutineScope(Dispatchers.IO).launch {
            fontDef.main.entries.map { (name, url) ->
                async {
                    try {
                        Log.i("Downloading $name from $url")
                        val ext = FILE_EXTENSIONS.firstOrNull { url.endsWith(it) } ?: ".ttf"
                        val file = File(fontsDir, "$name$ext")
                        if (file.exists()) return@async

                        val response: HttpResponse = HttpClient(CIO) {
                            install(UserAgent) { agent = Constants.USER_AGENT }
                            install(HttpRedirect) {}
                        }.use { it.get(url) }

                        if (response.status == HttpStatusCode.OK) {
                            file.writeBytes(response.body())
                        }
                    } catch (e: Throwable) {
                        Log.e("Failed to download font ($name from $url)", e)
                    }
                }
            }.awaitAll()
        }
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val fontFamilies: MutableList<FontFamily> = ArrayList()

        // Iterate over the list of fontFamilyNames, constructing new FontFamily objects
        // for use in the CustomFallbackBuilder below.
        for (fontFamilyName in fontFamilyNames) {
            try {
                for (fileExt in FILE_EXTENSIONS) {
                    val split = fontFamilyName.split(":")
                    if (split.size != 2) break
                    val (customName, refName) = split
                    val downloads = fontsDownloadsDir ?: break
                    val file = File(downloads, "$customName/$refName.$fileExt")
                    if (!file.exists()) continue
                    val font = Font.Builder(file).build()
                    fontFamilies.add(FontFamily.Builder(font).build())
                }
            } catch (_: Throwable) {
            }

            for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
                for (fileExt in FILE_EXTENSIONS) {
                    val fileName = "$fontRootPath$fontFamilyName$fileExt"
                    try {
                        val builder = if (fileName[0] == '/') Font.Builder(File(fileName))
                        else Font.Builder(assetManager, fileName)
                        val font = builder.build()
                        fontFamilies.add(FontFamily.Builder(font).build())
                    } catch (_: RuntimeException) {
                        continue
                    } catch (_: IOException) {
                        continue
                    }
                }
            }
        }

        // If there's some problem constructing fonts, fall back to the default behavior.
        if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

        val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
        for (i in 1 until fontFamilies.size) {
            fallbackBuilder.addCustomFallback(fontFamilies[i])
        }
        return fallbackBuilder.build()
    }

    private fun createAssetTypeface(
        rawName: String,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        // This logic attempts to safely check if the frontend code is attempting to use
        // fallback fonts, and if it is, to use the fallback typeface creation logic.
        val fontFamilyNames = rawName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

        var fontFamilyName = rawName
        // If there are multiple font family names:
        //   For newer versions of Android, construct a Typeface with fallbacks
        //   For older versions of Android, ignore all the fallbacks and just use the first font family
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS.getOrElse(style) { "" }

        try {
            for (fileExt in FILE_EXTENSIONS) {
                val split = fontFamilyName.split(":")
                if (split.size != 2) break
                val (customName, refName) = split
                val downloads = fontsDownloadsDir ?: break
                val file = File(downloads, "$customName/$refName.$fileExt")
                if (!file.exists()) continue
                return Typeface.createFromFile(file.absolutePath)
            }
        } catch (_: Throwable) {
        }

        // Lastly, after all those checks above, this is the original RN logic for
        // getting the typeface.
        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExt in FILE_EXTENSIONS) {
                val fileName = "$fontRootPath$fontFamilyName$extension$fileExt"
                return try {
                    if (fileName[0] == '/') Typeface.createFromFile(fileName)
                    else Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    // If the typeface asset does not exist, try another extension.
                    continue
                }
            }
        }

        return Typeface.create(fontFamilyName, style)
    }
}
