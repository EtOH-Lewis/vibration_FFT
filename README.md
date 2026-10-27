# Vibration FFT

Vibration FFT is a portrait-oriented Android app for viewing a live acceleration spectrum. It reads the device's linear acceleration, analyzes a sliding time window, and plots amplitude against angular frequency, `ω` (rad/s). The app also shows the three strongest distinct spectral peaks and their periods.

## Use the app

1. Open the app. The measurement starts in **End** state.
2. Set **FFT window** to a value from 1 to 60 seconds (10 seconds by default).
3. Tap **Start** to reset the elapsed time and spectrum and begin sampling. The first spectrum appears after nearly a full window of samples has been collected; it then updates about once per second.
4. Select **ax**, **ay**, **az**, or **atotal** at the bottom to choose the FFT input. **ax** is selected by default.
5. Tap **Pause** to freeze sampling, elapsed time, and the current spectrum. Tap **Start** to resume the same measurement.
6. Tap **End** to stop sampling while keeping the last spectrum and elapsed time visible. The next **Start** begins a new measurement from zero.

The chart shows `A` (m/s²) versus `ω` (rad/s). The peak table lists up to three reliable local peaks, ordered by amplitude, with angular frequency `ω`, period `T = 2π/ω` in seconds, and amplitude `A`. Missing peaks appear as `—`. The live readout shows `ax`, `ay`, `az`, `atotal`, and elapsed measurement time `t`, where `atotal = √(ax² + ay² + az²)`.

Tap **截图** at the top left of the chart to capture the complete app window as a PNG. On Android 10 and later, images are saved in `Pictures/VibrationFFT`. On Android 7–9, they are saved in the app-specific Pictures directory; the app displays the saved path in a toast.

## How the spectrum is calculated

The app prefers Android's `TYPE_LINEAR_ACCELERATION` sensor, which provides acceleration with gravity removed. If it is unavailable, the app uses `TYPE_ACCELEROMETER` and estimates gravity with a low-pass filter. Sensor delivery runs on a dedicated thread and requests a 10 ms sampling period; the FFT uses actual sensor timestamps rather than assuming that requested period was achieved.

Recent samples are kept in a fixed-capacity, time-pruned ring buffer. About once per second, a background coroutine copies the latest window and performs:

`Sensor → ring buffer → selected axis → sliding window → uniform resampling → mean removal → Hann window → FFT → A(ω) → peak detection → UI`

The analyzer estimates sampling rate from the observed timestamps and linearly resamples the selected signal onto an evenly spaced grid. It subtracts the mean, applies a Hann window, and computes a radix-2 FFT. Only positive frequencies are displayed; the zero-frequency (DC) bin is excluded. One-sided amplitudes use `2 × |FFT[k]| / sum(Hann weights)` (with the Nyquist bin handled separately), so a sinusoid's displayed amplitude remains approximately comparable across window lengths.

The FFT input is zero-padded for a smoother frequency grid. Zero-padding does **not** improve the true frequency resolution, which is approximately `Δf = 1 / window duration`. For example, a 1 s window resolves roughly 1 Hz, while a 10 s window resolves roughly 0.1 Hz. The app finds local maxima above a noise threshold and enforces a minimum separation to avoid listing adjacent bins of the same broad peak multiple times. The magnitude `atotal` is nonlinear, so its spectrum should not be interpreted as the spectrum of a single acceleration axis.

Sampling and the timer run only while the measurement is active and the app is in the foreground. Paused or background time is excluded from the measurement timeline. The raw-value display is throttled to about 20 updates per second to keep the Compose UI responsive.

## Build

Open the root directory in Android Studio with the Android SDK installed. The project uses Kotlin, Jetpack Compose, Gradle, `minSdk 24`, and `compileSdk 36.1`. No separate FFT library is required.

From the project root, build the debug APK and run unit tests:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Source layout

- `app/src/main/java/com/example/myapplication/sensor/AccelerationSensor.kt`: sensor acquisition and gravity fallback.
- `app/src/main/java/com/example/myapplication/fft/SampleRingBuffer.kt`: bounded sample storage.
- `app/src/main/java/com/example/myapplication/fft/FftAnalyzer.kt`: resampling, Hann window, FFT, amplitude scaling, and peak detection.
- `app/src/main/java/com/example/myapplication/fft/FftController.kt`: measurement state, elapsed time, and background spectrum updates.
- `app/src/main/java/com/example/myapplication/ui/FftScreen.kt`: Compose chart, controls, peak table, and live values.
- `app/src/main/java/com/example/myapplication/ui/ScreenCapture.kt`: PNG capture and saving.
