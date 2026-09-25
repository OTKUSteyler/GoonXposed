package GoonXposed.xposed.modules

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.Module
import GoonXposed.xposed.Utils.Log
import GoonXposed.xposed.hookMethod
import GoonXposed.xposed.safeLoadClass

/**
 * Compatibility layer for environments without Google Play Services (microG, GrapheneOS, de-Googled ROMs).
 *
 * Mocks GoogleApiAvailability and GooglePlayServicesUtil availability checks so Discord does not
 * hang, crash, or show "Google Play Services not available" prompts, and provides safe stubs for
 * Google Advertising ID queries.
 */
object GmsCompatModule : Module() {
    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        hookGoogleApiAvailability(classLoader)
        hookAdvertisingId(classLoader)
    }

    private fun hookGoogleApiAvailability(classLoader: ClassLoader) {
        listOf(
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.android.gms.common.GoogleApiAvailabilityLight",
            "com.google.android.gms.common.GooglePlayServicesUtil",
            "com.google.android.gms.common.GooglePlayServicesUtilLight"
        ).forEach { className ->
            val clazz = classLoader.safeLoadClass(className) ?: return@forEach
            // ConnectionResult.SUCCESS = 0
            clazz.hookMethod("isGooglePlayServicesAvailable", Context::class.java) {
                before {
                    result = 0
                }
            }
            clazz.hookMethod("isGooglePlayServicesAvailable", Context::class.java, Int::class.javaPrimitiveType) {
                before {
                    result = 0
                }
            }
            clazz.hookMethod("isUserResolvableError", Int::class.javaPrimitiveType) {
                before {
                    result = false
                }
            }
        }
    }

    private fun hookAdvertisingId(classLoader: ClassLoader) {
        val adClientClass = classLoader.safeLoadClass("com.google.android.gms.ads.identifier.AdvertisingIdClient") ?: return
        val adInfoClass = classLoader.safeLoadClass("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info")

        if (adInfoClass != null) {
            adClientClass.hookMethod("getAdvertisingIdInfo", Context::class.java) {
                before {
                    try {
                        val constructor = adInfoClass.getDeclaredConstructor(String::class.java, Boolean::class.javaPrimitiveType)
                        constructor.isAccessible = true
                        result = constructor.newInstance("00000000-0000-0000-0000-000000000000", true)
                        Log.i("GmsCompat: returned mock AdvertisingIdInfo")
                    } catch (e: Throwable) {
                        Log.w("GmsCompat: failed to create mock AdvertisingIdInfo: ${e.message}")
                    }
                }
            }
        }
    }
}
