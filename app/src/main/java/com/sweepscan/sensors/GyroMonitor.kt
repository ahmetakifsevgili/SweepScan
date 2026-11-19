package com.sweepscan.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Streams the gyroscope into a [GyroWindow] on its own thread. Sensor timestamps share the
 * camera's clock when the camera's timestamp source is REALTIME, so frame windows line up.
 */
class GyroMonitor(context: Context) : SensorEventListener {

    val window = GyroWindow()
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val tilt: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** Latest device → world rotation (3×3, row-major, z up) from the game rotation vector, or null. */
    @Volatile var rotation: FloatArray? = null
        private set
    private var thread: HandlerThread? = null

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        if (thread != null) return
        val t = HandlerThread("gyro").apply { start() }
        thread = t
        // 200 Hz (the highest rate without HIGH_SAMPLING_RATE_SENSORS): several samples between two
        // camera frames, so the rotation between frames can be integrated (see GyroWindow.rotationBetween).
        manager?.registerListener(this, s, SAMPLING_US, Handler(t.looper))
        // The phone's tilt: which shelf a label was on (ShelfLevel). 50 Hz is plenty.
        tilt?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper)) }
    }

    fun stop() {
        manager?.unregisterListener(this)
        rotation = null
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            rotation = FloatArray(9).also { SensorManager.getRotationMatrixFromVector(it, event.values) }
            return
        }
        window.add(event.timestamp, event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLING_US = 5_000
    }
}
