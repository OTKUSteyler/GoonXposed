@file:Suppress("DEPRECATION")

package GoonXposed.xposed.modules.appearance

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import GoonXposed.xposed.Constants
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Companion.JSON
import GoonXposed.xposed.Utils.Log
import GoonXposed.xposed.hookMethod
import GoonXposed.xposed.safeLoadClass
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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
    val main: Map<String, String> = emptyMap(),
)

/**
 * Custom font loading + ReactFontManager hijack.
 */
object FontsModule : Module() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Deprecated("This method is deprecated in the parent class")
    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.put("fontPatch", 2)
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        // ReactFontManager hijack runs regardless of fonts.json presence - it falls back to the
        // default Typeface chain if no custom font file is found.
        listOf(
            "com.facebook.react.common.assets.ReactFontManager\$Companion",
            "com.facebook.react.views.text.ReactFontManager\$Companion",
        ).forEach { clsName ->
            classLoader.safeLoadClass(clsName)?.let { cls ->
                runCatching {
                    cls.hookMethod(
                        "createAssetTypeface",
                        String::class.java,
                        Int::class.java,
                        AssetManager::class.java
                    ) {
                        before {
                            val fontFamilyName: String = args[0].toString()
                            val style: Int = args[1] as Int
                            val assetManager: AssetManager = args[2] as AssetManager
                            result = FontsState.createAssetTypeface(fontFamilyName, style, assetManager)
                        }
                    }
                }
            }
        }
    }

    override fun onContext(context: Context) {
        val dataDir = context.dataDir.absolutePath
        val fontDefFile = File(dataDir, "${Constants.FILES_DIR}/fonts.json")
        if (!fontDefFile.exists()) return

        val fontDef = try {
            JSON.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (e: Throwable) {
            Log.w("fonts.json malformed: ${e.message}")
            return
        }
        val setName = fontDef.name ?: return

        val downloadsDir = File(dataDir, "${Constants.FILES_DIR}/downloads/fonts").apply { mkdirs() }
        val setDir = File(downloadsDir, setName).apply { mkdirs() }
        FontsState.fontsDownloadsDir = downloadsDir
        FontsState.fontsAbsPath = setDir.absolutePath + "/"

        // Prune stale font files for this set.
        setDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    Log.i("Deleting stale font file: $fileName")
                    file.delete()
                }
            }
        }

        scope.launch {
            HttpClient(CIO) {
                expectSuccess = false
            }.use { client ->
                fontDef.main.entries.map { (name, url) ->
                    async {
                        try {
                            Log.i("Downloading $name from $url")
                            val ext = FontsState.FILE_EXTENSIONS.firstOrNull { url.endsWith(it) } ?: ".ttf"
                            val file = File(setDir, "$name$ext")
                            if (file.exists()) return@async
                            val response: HttpResponse = client.get(url)
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
    }
}

/**
 * Holds the per-process font-state used by the `createAssetTypeface` hijack. Mirrors the static
 * fields of the old `FontsModule`.
 */
private object FontsState {
    val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    const val FONTS_ASSET_PATH = "fonts/"

    @Volatile
    var fontsDownloadsDir: File? = null

    @Volatile
    var fontsAbsPath: String? = null

    fun createAssetTypeface(
        rawName: String,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        val fontFamilyNames = rawName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

        var fontFamilyName = rawName
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

        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExt in FILE_EXTENSIONS) {
                val fileName = "$fontRootPath$fontFamilyName$extension$fileExt"
                return try {
                    if (fileName[0] == '/') Typeface.createFromFile(fileName)
                    else Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    continue
                }
            }
        }
        return Typeface.create(fontFamilyName, style)
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val fontFamilies: MutableList<FontFamily> = ArrayList()
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

        if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

        val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
        for (i in 1 until fontFamilies.size) fallbackBuilder.addCustomFallback(fontFamilies[i])
        return fallbackBuilder.build()
    }
}
