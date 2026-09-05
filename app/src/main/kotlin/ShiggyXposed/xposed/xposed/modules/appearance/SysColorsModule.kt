package GoonXposed.xposed.modules.appearance

import android.R.color
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import de.robv.android.xposed.XposedHelpers
import GoonXposed.xposed.Module
import kotlinx.serialization.json.*
import java.lang.ref.WeakReference

object SysColorsModule : Module() {
    private lateinit var context: WeakReference<Context>
    private fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @Deprecated("This method is deprecated in the parent class")
    override fun buildPayload(builder: JsonObjectBuilder) {
        context = WeakReference(runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication"
            ) as Context
        }.getOrNull())
        val accents = arrayOf("accent1", "accent2", "accent3", "neutral1", "neutral2")
        val shades = arrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

        builder.apply {
            put("isSysColorsSupported", isSupported())
            if (isSupported()) putJsonObject("sysColors") {
                for (accent in accents) putJsonArray(accent) {
                    for (shade in shades) {
                        val colorName = "system_" + accent + "_" + shade

                        val colorResourceId = runCatching {
                            color::class.java.getField(colorName).getInt(null)
                        }.getOrElse { 0 }

                        add(convertToColor(colorResourceId))
                    }
                }
            }
        }
    }

    private fun convertToColor(id: Int): String {
        val ctx = context.get()
        val clr = if (isSupported() && ctx != null) ContextCompat.getColor(ctx, id) else 0
        return String.format("#%06X", 0xFFFFFF and clr)
    }
}
