package com.schmitzkr.grimreader.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Reports a firm shake of the phone. Acceleration is compared against
 * gravity so a resting phone at any angle reads as 1 g; a shake shows as
 * a spike well above it. One report per second at most.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var lastShakeMs = 0L

    /** False when the device has no accelerometer. */
    fun start(): Boolean {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        if (isShake(sqrt(x * x + y * y + z * z))) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShakeMs > DEBOUNCE_MS) {
                lastShakeMs = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val THRESHOLD_G = 2.7f
        const val DEBOUNCE_MS = 1_000L

        /** [gForce] is total acceleration in multiples of gravity. */
        fun isShake(gForce: Float): Boolean = gForce > THRESHOLD_G
    }
}
