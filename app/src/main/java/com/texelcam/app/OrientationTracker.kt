package com.texelcam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

enum class SurfaceMode {
    WALL,
    FLOOR
}

data class AlignmentState(
    val pitchDeg: Float,
    val rollDeg: Float,
    val pitchErrorDeg: Float,
    val rollErrorDeg: Float,
    val totalDeviationDeg: Float,
    val isAligned: Boolean,
    val mode: SurfaceMode
)

class OrientationTracker(
    private val context: Context,
    private val onOrientationChanged: (AlignmentState) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var currentMode: SurfaceMode = SurfaceMode.WALL
        set(value) {
            field = value
            wasAligned = false
            emaPitch = null
            emaRoll = null
        }

    private var wasAligned: Boolean = false
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var emaPitch: Float? = null
    private var emaRoll: Float? = null
    private val alpha = 0.2f // Smoothing factor


    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // orientationAngles:
            // [0]: azimuth/yaw
            // [1]: pitch (-pi/2 to +pi/2)
            // [2]: roll (-pi to +pi)
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            processAngles(pitchDeg, rollDeg)
        } else if (event.sensor.type == Sensor.TYPE_GRAVITY || event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val g = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
            if (g < 0.1f) return

            // Pitch & roll from gravity vector
            val pitchDeg = Math.toDegrees(asin((ay / g).toDouble().coerceIn(-1.0, 1.0))).toFloat()
            val rollDeg = Math.toDegrees(atan2(-ax.toDouble(), az.toDouble())).toFloat()

            processAngles(pitchDeg, rollDeg)
        }
    }

    private fun processAngles(pitchDeg: Float, rollDeg: Float) {
        emaPitch = if (emaPitch == null) pitchDeg else emaPitch!! + alpha * (pitchDeg - emaPitch!!)
        emaRoll = if (emaRoll == null) rollDeg else emaRoll!! + alpha * (rollDeg - emaRoll!!)

        val pitchError: Float
        val rollError: Float

        when (currentMode) {
            SurfaceMode.WALL -> {
                val devFromVertical = 90.0f - kotlin.math.abs(emaPitch!!)
                pitchError = devFromVertical
                rollError = emaRoll!!
            }
            SurfaceMode.FLOOR -> {
                pitchError = emaPitch!!
                rollError = emaRoll!!
            }
        }


        val totalDev = sqrt((pitchError * pitchError + rollError * rollError).toDouble()).toFloat()
        val isAligned = totalDev <= 0.8f // within 0.8 degrees of pure perpendicular

        // Trigger haptic bump when locking onto perfect perpendicular
        if (isAligned && !wasAligned) {
            triggerHapticClick()
        }
        wasAligned = isAligned

        val state = AlignmentState(
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            pitchErrorDeg = pitchError,
            rollErrorDeg = rollError,
            totalDeviationDeg = totalDev,
            isAligned = isAligned,
            mode = currentMode
        )

        onOrientationChanged(state)
    }

    private fun triggerHapticClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25)
            }
        } catch (_: Exception) {
            // Ignore if vibrator unavailable
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
