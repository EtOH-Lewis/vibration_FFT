package com.example.myapplication.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.sqrt

data class AccelerationSample(
    val timestampSeconds: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
) {
    val total: Double = sqrt(ax * ax + ay * ay + az * az)
}

/** Reads linear acceleration on a dedicated sensor thread. */
class AccelerationSensor(
    context: Context,
    private val onSample: (AccelerationSample) -> Unit,
    private val onAvailabilityChanged: (String) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val fallbackSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val sensorThread = HandlerThread("acceleration-sensor").apply { start() }
    private val handler = Handler(sensorThread.looper)

    private var running = false
    private var firstTimestampNs = 0L
    private var previousTimestampNs = 0L
    private val gravity = DoubleArray(3)
    private var gravityInitialized = false

    fun start() {
        if (running) return
        val sensor = linearSensor ?: fallbackSensor
        if (sensor == null) {
            onAvailabilityChanged("No acceleration sensor available")
            return
        }
        resetFilter()
        // 10 ms is a request, not an assumption; FFT derives fs from event timestamps.
        running = sensorManager.registerListener(this, sensor, 10_000, handler)
        onAvailabilityChanged(
            if (!running) "Unable to start acceleration sensor"
            else if (linearSensor != null) "Linear acceleration sensor"
            else "Accelerometer (gravity estimated)"
        )
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    fun close() {
        stop()
        sensorThread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (firstTimestampNs == 0L) firstTimestampNs = event.timestamp
        val values = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            doubleArrayOf(
                event.values[0].toDouble(),
                event.values[1].toDouble(),
                event.values[2].toDouble(),
            )
        } else {
            removeGravity(event)
        }
        onSample(
            AccelerationSample(
                timestampSeconds = (event.timestamp - firstTimestampNs) / 1_000_000_000.0,
                ax = values[0],
                ay = values[1],
                az = values[2],
            )
        )
        previousTimestampNs = event.timestamp
    }

    /** Low-pass estimates gravity only for devices lacking TYPE_LINEAR_ACCELERATION. */
    private fun removeGravity(event: SensorEvent): DoubleArray {
        if (!gravityInitialized) {
            repeat(3) { gravity[it] = event.values[it].toDouble() }
            gravityInitialized = true
        }
        val dt = if (previousTimestampNs == 0L) 0.01
        else ((event.timestamp - previousTimestampNs) / 1_000_000_000.0).coerceIn(0.001, 0.1)
        val timeConstantSeconds = 0.8
        val alpha = timeConstantSeconds / (timeConstantSeconds + dt)
        return DoubleArray(3) { axis ->
            gravity[axis] = alpha * gravity[axis] + (1.0 - alpha) * event.values[axis]
            event.values[axis] - gravity[axis]
        }
    }

    private fun resetFilter() {
        firstTimestampNs = 0L
        previousTimestampNs = 0L
        gravityInitialized = false
        gravity.fill(0.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
