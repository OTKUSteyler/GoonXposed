package ShiggyXposed.xposed.modules

import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Companion.JSON
import ShiggyXposed.xposed.Utils.Log
import android.app.Activity
import android.util.AtomicFile
import androidx.core.util.writeBytes
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

@Serializable data class CustomLoadUrl(val enabled: Boolean = false, val url: String = "")

@Serializable data class LoaderConfig(val customLoadUrl: CustomLoadUrl = CustomLoadUrl())

/**
 * Module that updates the JS bundle by downloading it from a remote URL.
 *
 * Shows dialogs when failed allowing retry.
 */
class UpdaterModule : Module() {
    private lateinit var config: LoaderConfig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var error: Throwable? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File

    companion object {
        var job: Job? = null

        private const val TIMEOUT_CACHED = 5000L
        private const val TIMEOUT = 50000L
        private const val ETAG_FILE = "etag.txt"
        private const val CONFIG_FILE = "loader.json"

        private const val DEFAULT_BUNDLE_URL =
                "https://github.com/kmmiio99o/ShiggyCord/releases/latest/download/shiggycord.js"
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) =
            with(packageParam) {
                cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
                val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

                bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
                etag = File(cacheDir, ETAG_FILE)

                val configFile = File(filesDir, CONFIG_FILE)

                config =
                        runCatching {
                                    if (configFile.exists()) {
                                        JSON.decodeFromString<LoaderConfig>(configFile.readText())
                                    } else LoaderConfig()
                                }
                                .getOrDefault(LoaderConfig())

                downloadScript()
            }

    private fun downloadScript(activity: Activity? = null) {
        job =
                scope.launch {
                    try {
                        HttpClient(CIO) {
                            expectSuccess = false
                            install(UserAgent) { agent = Constants.USER_AGENT }
                            install(HttpRedirect) {}
                        }
                                .use { client ->
                                    val url =
                                            config.customLoadUrl.takeIf { it.enabled }?.url
                                                    ?: DEFAULT_BUNDLE_URL
                                    Log.i("Fetching JS bundle from: $url")

                                    val response: HttpResponse =
                                            client.get(url) {
                                                headers {
                                                    if (etag.exists() && bundle.exists()) {
                                                        append(
                                                                HttpHeaders.IfNoneMatch,
                                                                etag.readText()
                                                        )
                                                    }
                                                }

                                                // Retries don't need timeout
                                                if (activity != null) {
                                                    val timeoutMs =
                                                            if (!bundle.exists()) TIMEOUT
                                                            else TIMEOUT_CACHED
                                                    timeout { requestTimeoutMillis = timeoutMs }
                                                }
                                            }

                                    when (response.status) {
                                        HttpStatusCode.OK -> {
                                            val bytes: ByteArray = response.body()
                                            AtomicFile(bundle).writeBytes(bytes)

                                            val newTag = response.headers[HttpHeaders.ETag]
                                            if (!newTag.isNullOrEmpty()) etag.writeText(newTag)
                                            else etag.delete()

                                            Log.i("Bundle updated (${bytes.size} bytes)")

                                            // This is a retry, so we show a dialog
                                            if (activity != null) {
                                                withContext(Dispatchers.Main) {}
                                            }
                                        }
                                        HttpStatusCode.NotModified -> {
                                            Log.i("Server responded with 304, no changes")
                                        }
                                        else -> {
                                            throw ResponseException(
                                                    response,
                                                    "Received status: ${response.status}"
                                            )
                                        }
                                    }
                                }
                    } catch (e: Throwable) {
                        Log.e("Failed to download script", e)
                        error = e
                    }
                }
    }

    override fun onActivity(activity: Activity) {
        error ?: return
        error = null
        downloadScript(activity)
    }
}
