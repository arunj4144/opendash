package com.opendash.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.opendash.app.logging.AppLogger
import com.opendash.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Detects whether the ENGINE is running from the phone's accelerometer: a
 * single running on the bike (mounted or pocketed) puts a distinct vibration
 * floor under the phone that survives suspension and jacket padding. This is a
 * different signal than the BLE link (the dash stays powered with the kill
 * switch on, engine dead) and different from GPS speed (idling at a light is
 * engine-on, speed 0) - together they tell "waiting in traffic" apart from
 * "parked and done".
 *
 * Signal: windowed RMS of the accelerometer magnitude with the window's own
 * mean removed (cheap high-pass that cancels gravity and slow phone tilt).
 * The engine-on threshold sits between the two RMS levels captured by the
 * guided calibration (bike off vs idling), adjustable with a sensitivity trim
 * and refined gradually while riding (speed > 10 km/h is engine-on for sure).
 *
 * Power: on battery (with power-save on) it samples a short window every
 * cycle instead of running the sensor continuously; charging runs continuous
 * windows back-to-back.
 */
class VibrationMonitor(private val context: Context) : SensorEventListener {

    companion object {
        /** One measurement window; long enough to average over engine strokes. */
        private const val WINDOW_MS = 2_500L
        /** Battery duty cycle: one window per this period (power-save mode). */
        private const val DUTY_PERIOD_MS = 15_000L
        /** EMA weight for the gradual riding-time refinement of the engine level. */
        private const val LEARN_ALPHA = 0.05f
        /** Debounce: state flips only after two consecutive windows agree. */
        private const val CONFIRM_WINDOWS = 2

        /** Live engine state: true/false once calibrated and sampling, null = unknown. */
        private val _engineOn = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
        val engineOn: kotlinx.coroutines.flow.StateFlow<Boolean?> = _engineOn

        /** Most recent window RMS (m/s²), for the calibration/tuning UI. */
        private val _lastRms = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
        val lastRms: kotlinx.coroutines.flow.StateFlow<Float?> = _lastRms

        /**
         * One-shot RMS capture for the guided calibration: samples for
         * [durationMs] and returns the window RMS, bypassing thresholds and the
         * duty cycle. Runs its own listener so it works while the monitor is off.
         */
        suspend fun captureRms(context: Context, durationMs: Long): Float? {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
            val samples = ArrayList<Float>(1024)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val (x, y, z) = e.values
                    samples.add(sqrt(x * x + y * y + z * z))
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            try {
                delay(durationMs)
            } finally {
                sm.unregisterListener(listener)
            }
            return rmsOf(samples)
        }

        /** RMS of the mean-removed magnitude series; null if too few samples. */
        private fun rmsOf(samples: List<Float>): Float? {
            if (samples.size < 20) return null
            val mean = samples.sum() / samples.size
            var acc = 0.0
            for (s in samples) { val d = s - mean; acc += d * d }
            return sqrt(acc / samples.size).toFloat()
        }
    }

    private val settings = AppSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val window = ArrayList<Float>(512)
    private val windowLock = Any()
    private var agreeCount = 0
    private var lastDecision: Boolean? = null

    /** Live GPS speed injected by the service (from SpeedMonitor) for the gradual learning. */
    @Volatile var currentSpeedKmh: Float? = null

    fun start() {
        if (loopJob != null) return
        if (!settings.engineDetectEnabled) return
        if (settings.vibrationIdleRms.isNaN() || settings.vibrationEngineRms.isNaN()) {
            AppLogger.log("Vibe", "Engine detect enabled but uncalibrated - run calibration first")
            return
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            AppLogger.log("Vibe", "No accelerometer on this device")
            return
        }
        AppLogger.log("Vibe", "Engine-vibration monitor started (idle=%.4f engine=%.4f)"
            .format(settings.vibrationIdleRms, settings.vibrationEngineRms))
        loopJob = scope.launch {
            while (isActive) {
                synchronized(windowLock) { window.clear() }
                sm.registerListener(this@VibrationMonitor, accel, SensorManager.SENSOR_DELAY_GAME)
                delay(WINDOW_MS)
                sm.unregisterListener(this@VibrationMonitor)
                val rms = rmsOf(synchronized(windowLock) { ArrayList(window) })
                if (rms != null) evaluate(rms)
                // Charging (or power-save off): sample continuously. On battery
                // with power-save on: one window per duty period.
                val powerSaving = settings.powerSaveEnabled && !isCharging()
                if (powerSaving) delay(DUTY_PERIOD_MS - WINDOW_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel(); loopJob = null
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        runCatching { sm.unregisterListener(this) }
        _engineOn.value = null
        _lastRms.value = null
        agreeCount = 0; lastDecision = null
    }

    /** Settings changed (enable toggle / recalibration): restart with fresh thresholds. */
    fun reevaluate() {
        stop()
        start()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun evaluate(rms: Float) {
        _lastRms.value = rms
        val idle = settings.vibrationIdleRms
        val engine = settings.vibrationEngineRms
        // Threshold at the geometric midpoint (levels differ by ratio, not
        // offset), shifted by the user's sensitivity trim.
        val mid = sqrt(idle * engine)
        val threshold = mid * (1f + settings.vibrationSensitivity / 100f)
        val decision = rms > threshold

        if (decision == lastDecision) agreeCount++ else { agreeCount = 1; lastDecision = decision }
        if (agreeCount >= CONFIRM_WINDOWS && _engineOn.value != decision) {
            _engineOn.value = decision
            AppLogger.log("Vibe", "Engine ${if (decision) "ON" else "OFF"} (rms=%.4f thr=%.4f)".format(rms, threshold))
        }

        // Gradual learning: while demonstrably riding, the measured level IS the
        // engine level for this phone/mount/user - fold it in slowly so the
        // calibration tracks how the rider actually carries the phone. (Riding
        // vibration runs above pure idle, so learn conservatively: only walk the
        // stored engine level DOWN toward gentler readings, never up - keeping
        // a rough ride from inflating the threshold past a soft idle.)
        val speed = currentSpeedKmh
        if (speed != null && speed > 10f && rms < engine) {
            val updated = engine * (1 - LEARN_ALPHA) + rms * LEARN_ALPHA
            settings.vibrationEngineRms = updated
        }
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        val mag = sqrt(x * x + y * y + z * z)
        synchronized(windowLock) { window.add(mag) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
