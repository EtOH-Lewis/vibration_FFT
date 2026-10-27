package com.example.myapplication.fft

import com.example.myapplication.sensor.AccelerationSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SignalAxis(val label: String) { AX("ax"), AY("ay"), AZ("az"), TOTAL("atotal") }

data class SpectrumPoint(val omega: Double, val amplitude: Double)
data class SpectrumPeak(val omega: Double, val amplitude: Double)

data class FftResult(
    val points: List<SpectrumPoint>,
    val peaks: List<SpectrumPeak>,
    val samplingRateHz: Double,
    val measuredDurationSeconds: Double,
)

object FftAnalyzer {
    fun analyze(
        samples: List<AccelerationSample>,
        axis: SignalAxis,
        requestedWindowSeconds: Double,
    ): FftResult? {
        if (samples.size < 8) return null
        val duration = samples.last().timestampSeconds - samples.first().timestampSeconds
        // Do not publish a spectrum until almost the full requested physical window exists.
        if (duration < requestedWindowSeconds * 0.9 || duration <= 0.0) return null

        // Average fs comes from actual SensorEvent timestamps, never the requested sensor period.
        val samplingRate = (samples.size - 1) / duration
        if (!samplingRate.isFinite() || samplingRate <= 0.0) return null

        // Sensor timestamps are slightly irregular, so interpolate onto an equally spaced grid.
        // +1 retains both physical endpoints: duration * fs equals interval count.
        val uniformCount = ((duration * samplingRate).roundToInt() + 1).coerceAtLeast(8)
        val uniform = resample(samples, axis, uniformCount)
        val mean = uniform.average()
        // Fourfold padding reduces display-bin scalloping. It interpolates the FFT only;
        // the independent frequency resolution is still approximately 1 / duration.
        val fftSize = nextPowerOfTwo(uniformCount * 4)
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)

        var windowSum = 0.0
        for (i in uniform.indices) {
            val hann = if (uniform.size == 1) 1.0
            else 0.5 * (1.0 - cos(2.0 * PI * i / (uniform.size - 1)))
            real[i] = (uniform[i] - mean) * hann
            windowSum += hann
        }
        fft(real, imaginary)

        val points = ArrayList<SpectrumPoint>(fftSize / 2)
        // One-sided amplitude: 2|X[k]| / sum(Hann). This keeps sinusoid amplitude
        // approximately invariant when the time-window length changes. Padding only
        // interpolates the displayed spectrum; true resolution remains about 1/T.
        for (k in 1..fftSize / 2) {
            val frequencyHz = k * samplingRate / fftSize
            var amplitude = 2.0 * hypot(real[k], imaginary[k]) / windowSum
            if (k == fftSize / 2) amplitude *= 0.5
            points += SpectrumPoint(omega = 2.0 * PI * frequencyHz, amplitude = amplitude)
        }

        return FftResult(
            points = points,
            peaks = detectPeaks(points, duration),
            samplingRateHz = samplingRate,
            measuredDurationSeconds = duration,
        )
    }

    private fun resample(
        samples: List<AccelerationSample>,
        axis: SignalAxis,
        count: Int,
    ): DoubleArray {
        val start = samples.first().timestampSeconds
        val end = samples.last().timestampSeconds
        val step = (end - start) / (count - 1)
        var right = 1
        return DoubleArray(count) { i ->
            val target = start + i * step
            while (right < samples.lastIndex && samples[right].timestampSeconds < target) right++
            val before = samples[right - 1]
            val after = samples[right]
            val span = after.timestampSeconds - before.timestampSeconds
            val fraction = if (span <= 0.0) 0.0 else ((target - before.timestampSeconds) / span).coerceIn(0.0, 1.0)
            valueOf(before, axis) + fraction * (valueOf(after, axis) - valueOf(before, axis))
        }
    }

    private fun valueOf(sample: AccelerationSample, axis: SignalAxis): Double = when (axis) {
        SignalAxis.AX -> sample.ax
        SignalAxis.AY -> sample.ay
        SignalAxis.AZ -> sample.az
        SignalAxis.TOTAL -> sample.total
    }

    internal fun detectPeaks(points: List<SpectrumPoint>, duration: Double): List<SpectrumPeak> {
        if (points.size < 3 || duration <= 0.0) return emptyList()
        val amplitudes = points.map { it.amplitude }.sorted()
        val noiseFloor = amplitudes[amplitudes.size / 2]
        val maximum = amplitudes.last()
        val threshold = maxOf(noiseFloor * 4.0, maximum * 0.04, 1e-6)
        val minOmega = 2.0 * PI * maxOf(0.25, 1.0 / duration)
        val minPeakSpacing = 2.0 * PI * maxOf(0.5, 2.0 / duration)

        val localPeaks = (1 until points.lastIndex)
            .filter { index ->
                val p = points[index]
                p.omega >= minOmega && p.amplitude >= threshold &&
                    p.amplitude > points[index - 1].amplitude &&
                    p.amplitude >= points[index + 1].amplitude
            }
            .map { SpectrumPeak(points[it].omega, points[it].amplitude) }
            .sortedByDescending { it.amplitude }

        val selected = mutableListOf<SpectrumPeak>()
        for (candidate in localPeaks) {
            if (selected.none { kotlin.math.abs(it.omega - candidate.omega) < minPeakSpacing }) {
                selected += candidate
                if (selected.size == 3) break
            }
        }
        return selected
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realTemp = real[i]
                real[i] = real[j]
                real[j] = realTemp
                val imaginaryTemp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryTemp
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val baseReal = cos(angle)
            val baseImaginary = sin(angle)
            var start = 0
            while (start < n) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWReal = wReal * baseReal - wImaginary * baseImaginary
                    wImaginary = wReal * baseImaginary + wImaginary * baseReal
                    wReal = nextWReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}
