package ch.offgrid.android.domain

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface

/**
 * Phone heading to true north from the rotation vector, or from accelerometer and magnetometer when a
 * phone has no rotation vector. Readings are smoothed with [HeadingSmoother], follow the display
 * rotation and are corrected by the magnetic declination at [declinationFix]. Callbacks run on the
 * main thread. [start] and [stop] follow the visible lifecycle of the screen that shows the compass.
 */
class CompassSensor(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** False when the phone cannot tell its heading; the screen then shows the direction in words only. */
    val available: Boolean = rotationVector != null || (accelerometer != null && magnetometer != null)

    private val smoother = HeadingSmoother()
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravity: FloatArray? = null
    private var declination = 0f
    private var declinationKey: GeoFix? = null
    private var displayRotation: () -> Int = { Surface.ROTATION_0 }
    private var onHeading: (Double) -> Unit = {}
    private var onUnreliable: (Boolean) -> Unit = {}
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    // x, y, z and the scalar part are enough; some older devices reject the extra
                    // heading-accuracy value that newer ones append.
                    SensorManager.getRotationMatrixFromVector(rotation, event.values.copyOf(minOf(event.values.size, 4)))
                    publish()
                }
                Sensor.TYPE_ACCELEROMETER -> gravity = event.values.copyOf()
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val g = gravity ?: return
                    if (SensorManager.getRotationMatrix(rotation, null, g, event.values)) publish()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // The rotation vector inherits the magnetometer's calibration; in the fallback only the
            // magnetometer decides, the accelerometer does not need a figure eight.
            if (sensor.type == Sensor.TYPE_ROTATION_VECTOR || sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                onUnreliable(accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
            }
        }
    }

    private fun publish() {
        val (x, y) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val magnetic = Math.toDegrees(orientation[0].toDouble())
        onHeading(smoother.update(Heading.normalize(magnetic + declination), SystemClock.elapsedRealtime()))
    }

    /** Magnetic declination at the own position; without a fix the heading stays magnetic. */
    fun setDeclinationFix(fix: GeoFix?) {
        if (fix == declinationKey) return
        declinationKey = fix
        declination = fix?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, it.at).declination } ?: 0f
    }

    fun start(displayRotation: () -> Int, onHeading: (Double) -> Unit, onUnreliable: (Boolean) -> Unit) {
        if (running || manager == null || !available) return
        running = true
        this.displayRotation = displayRotation
        this.onHeading = onHeading
        this.onUnreliable = onUnreliable
        smoother.reset()
        gravity = null
        if (rotationVector != null) {
            manager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
        } else {
            manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(listener)
        onHeading = {}
        onUnreliable = {}
    }
}
