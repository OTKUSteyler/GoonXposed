package GoonXposed.xposed

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import GoonXposed.xposed.modules.appearance.FontsModule

class MainModule : IXposedHookLoadPackage {
    private var lastShakeTime: Long = 0
    private val SHAKE_THRESHOLD = 15f
    private val SHAKE_INTERVAL_MS = 500L

    override fun handleLoadPackage(lparam: XC_LoadPackage.LoadPackageParam) {
        // Load the fonts module
        FontsModule.onLoad(lparam)
        
        XposedBridge.log("GoonXposed: FontsModule loaded for ${lparam.packageName}")

        // Hook into the main activity to detect shakes
        try {
            val activityClass = Class.forName("android.app.Activity")
            val method = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    setupShakeDetection(activity)
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("GoonXposed: Failed to hook activity: ${e.message}")
        }
    }

    private fun setupShakeDetection(activity: Activity) {
        try {
            val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            if (accelerometer != null) {
                sensorManager.registerListener(object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]

                        val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                        val currentTime = System.currentTimeMillis()

                        if (acceleration > SHAKE_THRESHOLD && 
                            currentTime - lastShakeTime > SHAKE_INTERVAL_MS) {
                            lastShakeTime = currentTime
                            onShakeDetected(activity)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }, accelerometer, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Throwable) {
            XposedBridge.log("GoonXposed: Failed to setup shake detection: ${e.message}")
        }
    }

    private fun onShakeDetected(activity: Activity) {
        XposedBridge.log("GoonXposed: Shake detected!")
        // You can show a toast or dialog here
        // Or launch your UI
    }
}
