package com.example.myapplication.fft

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.example.myapplication.sensor.AccelerationSample
import com.example.myapplication.sensor.AccelerationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SessionState { END, RUNNING, PAUSED }

@Immutable
data class SpectrumDisplay(
    val spectrum: List<SpectrumPoint> = emptyList(),
    val peaks: List<SpectrumPeak> = emptyList(),
    val samplingRateHz: Double? = null,
    val collecting: Boolean = true,
    val amplitudeAxisMax: Double = 1.0,
    val omegaAxisMax: Double = 10.0,
)

data class FftUiState(
    val latest: AccelerationSample? = null,
    val display: SpectrumDisplay = SpectrumDisplay(),
    val selectedAxis: SignalAxis = SignalAxis.AX,
    val windowSeconds: Double = 10.0,
    val elapsedSeconds: Double = 0.0,
    val sessionState: SessionState = SessionState.END,
    val sensorStatus: String = "End",
)

class FftController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ringBuffer = SampleRingBuffer()
    private val mutableState = MutableStateFlow(FftUiState())
    val state: StateFlow<FftUiState> = mutableState.asStateFlow()

    @Volatile private var hostInForeground = false
    @Volatile private var sessionState = SessionState.END
    @Volatile private var accumulatedSeconds = 0.0
    @Volatile private var activeSegmentBaseSeconds = 0.0
    @Volatile private var activeSegmentStartedNs: Long? = null
    private var fftJob: Job? = null
    private var clockJob: Job? = null
    private var lastRawUiUpdateNs = 0L
    private val sensor = AccelerationSensor(
        context = context,
        onSample = ::onSample,
        onAvailabilityChanged = { status ->
            if (sessionState == SessionState.RUNNING) {
                mutableState.value = mutableState.value.copy(sensorStatus = status)
            }
        },
    )

    /** Lifecycle visibility never starts a new measurement by itself. */
    @Synchronized
    fun onHostStart() {
        hostInForeground = true
        if (sessionState == SessionState.RUNNING && activeSegmentStartedNs == null) beginActiveSampling()
    }

    @Synchronized
    fun onHostStop() {
        hostInForeground = false
        if (sessionState == SessionState.RUNNING) stopActiveSampling("Sampling suspended in background")
    }

    /** Starts fresh only from End; resumes the retained timeline from Pause. */
    @Synchronized
    fun start() {
        when (sessionState) {
            SessionState.END -> {
                ringBuffer.clear()
                accumulatedSeconds = 0.0
                activeSegmentBaseSeconds = 0.0
                mutableState.value = mutableState.value.copy(
                    latest = null,
                    display = SpectrumDisplay(),
                    elapsedSeconds = 0.0,
                    sessionState = SessionState.RUNNING,
                    sensorStatus = "Starting sensor…",
                )
            }
            SessionState.PAUSED -> {
                mutableState.value = mutableState.value.copy(
                    sessionState = SessionState.RUNNING,
                    sensorStatus = "Resuming sensor…",
                )
            }
            SessionState.RUNNING -> return
        }
        sessionState = SessionState.RUNNING
        if (hostInForeground) beginActiveSampling()
    }

    /** Freezes sampling and its current spectrum without clearing either. */
    @Synchronized
    fun pause() {
        if (sessionState != SessionState.RUNNING) return
        stopActiveSampling("Paused")
        sessionState = SessionState.PAUSED
        mutableState.value = mutableState.value.copy(
            elapsedSeconds = accumulatedSeconds,
            sessionState = SessionState.PAUSED,
            sensorStatus = "Paused",
        )
    }

    /** Stops sampling without clearing the spectrum or resetting elapsed time. */
    @Synchronized
    fun end() {
        if (sessionState == SessionState.END) return
        if (sessionState == SessionState.RUNNING) stopActiveSampling("Ended")
        sessionState = SessionState.END
        mutableState.value = mutableState.value.copy(
            elapsedSeconds = accumulatedSeconds,
            sessionState = SessionState.END,
            sensorStatus = "End",
        )
    }

    fun close() {
        sensor.close()
        scope.cancel()
    }

    fun selectAxis(axis: SignalAxis) {
        val current = mutableState.value
        mutableState.value = current.copy(
            selectedAxis = axis,
            display = current.display.copy(collecting = true),
        )
        scope.launch { calculateSpectrum() }
    }

    fun setWindowSeconds(seconds: Double) {
        if (seconds !in 1.0..60.0) return
        ringBuffer.retentionSeconds = seconds + 2.0
        mutableState.value = mutableState.value.copy(
            windowSeconds = seconds,
            display = SpectrumDisplay(),
        )
        if (sessionState == SessionState.RUNNING) scope.launch { calculateSpectrum() }
    }

    @Synchronized
    private fun beginActiveSampling() {
        if (activeSegmentStartedNs != null || sessionState != SessionState.RUNNING) return
        activeSegmentBaseSeconds = accumulatedSeconds
        activeSegmentStartedNs = SystemClock.elapsedRealtimeNanos()
        sensor.start()
        startWorkers()
    }

    @Synchronized
    private fun stopActiveSampling(status: String) {
        accumulatedSeconds = currentElapsedSeconds()
        activeSegmentStartedNs = null
        sensor.stop()
        fftJob?.cancel()
        clockJob?.cancel()
        fftJob = null
        clockJob = null
        mutableState.value = mutableState.value.copy(
            elapsedSeconds = accumulatedSeconds,
            sensorStatus = status,
        )
    }

    private fun startWorkers() {
        fftJob?.cancel()
        clockJob?.cancel()
        fftJob = scope.launch {
            while (isActive && sessionState == SessionState.RUNNING && hostInForeground) {
                calculateSpectrum()
                delay(1_000)
            }
        }
        clockJob = scope.launch {
            while (isActive && sessionState == SessionState.RUNNING && hostInForeground) {
                publishElapsedTime()
                delay(100)
            }
        }
    }

    private fun publishElapsedTime() {
        mutableState.value = mutableState.value.copy(elapsedSeconds = currentElapsedSeconds())
    }

    private fun currentElapsedSeconds(): Double {
        val started = activeSegmentStartedNs ?: return accumulatedSeconds
        return activeSegmentBaseSeconds + (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000_000.0
    }

    private fun onSample(sample: AccelerationSample) {
        if (sessionState != SessionState.RUNNING || activeSegmentStartedNs == null) return
        // Preserve precise SensorEvent spacing while excluding time spent paused/backgrounded.
        val sessionSample = sample.copy(timestampSeconds = activeSegmentBaseSeconds + sample.timestampSeconds)
        ringBuffer.add(sessionSample)
        // Raw values are capped at 20 Hz so sensor delivery never drives full-rate recomposition.
        val now = System.nanoTime()
        if (now - lastRawUiUpdateNs >= 50_000_000L) {
            lastRawUiUpdateNs = now
            mutableState.value = mutableState.value.copy(latest = sessionSample)
        }
    }

    private fun calculateSpectrum() {
        val before = mutableState.value
        val samples = ringBuffer.latestWindow(before.windowSeconds)
        val result = FftAnalyzer.analyze(samples, before.selectedAxis, before.windowSeconds)
        val current = mutableState.value
        if (current.sessionState != SessionState.RUNNING ||
            current.selectedAxis != before.selectedAxis ||
            current.windowSeconds != before.windowSeconds
        ) return
        if (result == null) {
            mutableState.value = current.copy(display = current.display.copy(collecting = true))
            return
        }

        val targetAmplitude = (result.points.maxOfOrNull { it.amplitude } ?: 0.0) * 1.15
        val targetOmega = result.points.lastOrNull()?.omega ?: 10.0
        val oldDisplay = current.display
        mutableState.value = current.copy(display = SpectrumDisplay(
            spectrum = result.points,
            peaks = result.peaks,
            samplingRateHz = result.samplingRateHz,
            collecting = false,
            amplitudeAxisMax = smoothAxis(oldDisplay.amplitudeAxisMax, targetAmplitude, floor = 0.01),
            omegaAxisMax = smoothAxis(oldDisplay.omegaAxisMax, targetOmega, floor = 1.0),
        ))
    }

    private fun smoothAxis(previous: Double, target: Double, floor: Double): Double {
        val safeTarget = target.coerceAtLeast(floor)
        return if (safeTarget > previous) safeTarget else previous * 0.65 + safeTarget * 0.35
    }
}
