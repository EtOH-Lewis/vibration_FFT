package com.example.myapplication.fft

import com.example.myapplication.sensor.AccelerationSample

/** Fixed-capacity, time-pruned buffer. All access is synchronized across sensor/FFT threads. */
class SampleRingBuffer(private val capacity: Int = 32_768) {
    private val samples = ArrayDeque<AccelerationSample>(capacity)

    @Volatile
    var retentionSeconds: Double = 12.0

    @Synchronized
    fun add(sample: AccelerationSample) {
        samples.addLast(sample)
        val oldestAllowed = sample.timestampSeconds - retentionSeconds
        while (samples.isNotEmpty() &&
            (samples.first().timestampSeconds < oldestAllowed || samples.size > capacity)
        ) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun latestWindow(seconds: Double): List<AccelerationSample> {
        if (samples.isEmpty()) return emptyList()
        val start = samples.last().timestampSeconds - seconds
        return samples.filter { it.timestampSeconds >= start }
    }

    @Synchronized
    fun clear() = samples.clear()
}
