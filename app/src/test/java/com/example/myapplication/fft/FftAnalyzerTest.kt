package com.example.myapplication.fft

import com.example.myapplication.sensor.AccelerationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftAnalyzerTest {
    @Test
    fun findsSinusoidFrequencyAndAmplitudeWithIrregularTimestamps() {
        val frequencyHz = 7.0
        val amplitude = 1.8
        var time = 0.0
        val samples = buildList {
            while (time <= 10.0) {
                add(
                    AccelerationSample(
                        timestampSeconds = time,
                        ax = 3.2 + amplitude * sin(2.0 * PI * frequencyHz * time),
                        ay = 0.0,
                        az = 0.0,
                    )
                )
                time += if (size % 3 == 0) 0.0095 else 0.01025
            }
        }

        val result = FftAnalyzer.analyze(samples, SignalAxis.AX, 10.0)
        assertNotNull(result)
        val peak = result!!.peaks.first()
        assertEquals(2.0 * PI * frequencyHz, peak.omega, 0.2)
        assertEquals(amplitude, peak.amplitude, 0.12)
        assertTrue(result.points.none { it.omega == 0.0 })
    }

    @Test
    fun hannNormalizationIsStableAcrossWindowLengths() {
        fun signal(duration: Double): List<AccelerationSample> = buildList {
            var time = 0.0
            while (time <= duration) {
                add(AccelerationSample(time, 2.0 * sin(2.0 * PI * 4.0 * time), 0.0, 0.0))
                time += 0.01
            }
        }

        val fiveSecondAmplitude = FftAnalyzer.analyze(signal(5.0), SignalAxis.AX, 5.0)!!.peaks.first().amplitude
        val tenSecondAmplitude = FftAnalyzer.analyze(signal(10.0), SignalAxis.AX, 10.0)!!.peaks.first().amplitude

        assertEquals(fiveSecondAmplitude, tenSecondAmplitude, 0.03)
        assertEquals(2.0, tenSecondAmplitude, 0.05)
    }

    @Test
    fun reportsOnlySeparatedLocalPeaks() {
        val points = listOf(
            0.001, 0.003, 0.01, 1.0, 0.9, 0.8, 0.01, 0.002,
            0.004, 0.01, 0.7, 0.01, 0.002, 0.003, 0.001,
        )
            .mapIndexed { index, amplitude -> SpectrumPoint(index * 4.0, amplitude) }
        val peaks = FftAnalyzer.detectPeaks(points, duration = 10.0)

        assertEquals(2, peaks.size)
        assertEquals(12.0, peaks[0].omega, 0.0)
        assertEquals(40.0, peaks[1].omega, 0.0)
    }
}
