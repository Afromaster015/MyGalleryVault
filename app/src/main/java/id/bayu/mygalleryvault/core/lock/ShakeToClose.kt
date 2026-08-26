package id.bayu.mygalleryvault.core.lock

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import id.bayu.mygalleryvault.core.config.GlobalConfig
import id.bayu.mygalleryvault.core.crypto.VaultSession
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shake-to-close per PRD §30: registers the accelerometer while resumed and
 * locks the vault when a shake above the configured sensitivity is detected.
 */
object ShakeToClose : SensorEventListener {

    private const val MIN_INTERVAL_MS = 900L

    private var sensorManager: SensorManager? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastShakeAt = 0L
    private var initialized = false

    fun start(activity: Activity) {
        val manager = sensorManager
            ?: activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = manager ?: return
        manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!GlobalConfig.shakeEnabled || !VaultSession.isUnlocked.value) {
            record(event)
            return
        }
        val x = event.values[0]
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        if (!initialized) {
            record(x, y, z)
            return
        }
        val delta = sqrt(
            abs(lastX - x).pow2() + abs(lastY - y).pow2() + abs(lastZ - z).pow2(),
        )
        record(x, y, z)
        val now = System.currentTimeMillis()
        if (delta > GlobalConfig.shakeThreshold && now - lastShakeAt > MIN_INTERVAL_MS) {
            lastShakeAt = now
            VaultSession.lockNow()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun Float.pow2(): Float = this * this

    private fun record(event: SensorEvent) {
        record(
            event.values.getOrElse(0) { 0f },
            event.values.getOrElse(1) { 0f },
            event.values.getOrElse(2) { 0f },
        )
    }

    private fun record(x: Float, y: Float, z: Float) {
        lastX = x
        lastY = y
        lastZ = z
        initialized = true
    }
}
