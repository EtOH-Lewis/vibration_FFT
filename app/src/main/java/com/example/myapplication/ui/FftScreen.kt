package com.example.myapplication.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withSave
import com.example.myapplication.fft.FftController
import com.example.myapplication.fft.FftUiState
import com.example.myapplication.fft.SignalAxis
import com.example.myapplication.fft.SpectrumDisplay
import com.example.myapplication.fft.SessionState
import kotlin.math.PI
import java.util.Locale

@Composable
fun FftScreen(controller: FftController, onCaptureScreen: () -> Unit) {
    val state by controller.state.collectAsState()
    val display = state.display
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SpectrumChart(
                display = display,
                status = chartStatus(state),
                onCaptureScreen = onCaptureScreen,
                modifier = Modifier.weight(1f),
            )
            PeakTable(display)
            WindowEditor(
                currentSeconds = state.windowSeconds,
                onValidValue = controller::setWindowSeconds,
                sessionState = state.sessionState,
                onStart = controller::start,
                onPause = controller::pause,
                onEnd = controller::end,
            )
            RawValues(state)
            AxisSelector(selected = state.selectedAxis, onSelect = controller::selectAxis)
        }
    }
}

@Composable
private fun SpectrumChart(
    display: SpectrumDisplay,
    status: String,
    onCaptureScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val density = LocalDensity.current
    val labelSizePx = with(density) { 10.sp.toPx() }

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = background) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val left = 46.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 14.dp.toPx()
                val bottom = size.height - 35.dp.toPx()
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)
                val safeOmegaMax = display.omegaAxisMax.coerceAtLeast(1.0)
                val safeAmplitudeMax = display.amplitudeAxisMax.coerceAtLeast(0.01)

                repeat(5) { tick ->
                    val fraction = tick / 4f
                    val x = left + width * fraction
                    val y = bottom - height * fraction
                    drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                    drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
                drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

                if (display.spectrum.size > 1) {
                    val path = Path()
                    display.spectrum.forEachIndexed { index, point ->
                        val x = left + (point.omega / safeOmegaMax).coerceIn(0.0, 1.0).toFloat() * width
                        val y = bottom - (point.amplitude / safeAmplitudeMax).coerceIn(0.0, 1.0).toFloat() * height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (axisColor.alpha * 255).toInt(),
                        (axisColor.red * 255).toInt(),
                        (axisColor.green * 255).toInt(),
                        (axisColor.blue * 255).toInt(),
                    )
                    textSize = labelSizePx
                    textAlign = Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.apply {
                    for (tick in 0..4) {
                        val fraction = tick / 4f
                        drawText(
                            format(safeOmegaMax * fraction, if (safeOmegaMax < 10) 1 else 0),
                            left + width * fraction,
                            bottom + 15.dp.toPx(),
                            paint,
                        )
                    }
                    paint.textAlign = Paint.Align.RIGHT
                    for (tick in 0..4) {
                        val fraction = tick / 4f
                        drawText(
                            format(safeAmplitudeMax * fraction, 2),
                            left - 5.dp.toPx(),
                            bottom - height * fraction + 3.dp.toPx(),
                            paint,
                        )
                    }
                    paint.textAlign = Paint.Align.CENTER
                    drawText("ω / rad·s⁻¹", left + width / 2, size.height - 4.dp.toPx(), paint)
                    withSave {
                        rotate(-90f, 10.dp.toPx(), top + height / 2)
                        drawText("A / m·s⁻²", 10.dp.toPx(), top + height / 2, paint)
                    }
                }
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
            OutlinedButton(
                onClick = onCaptureScreen,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("截图", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PeakTable(display: SpectrumDisplay) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        PeakRow("Peak", "ω / rad·s⁻¹", "T / s", "A", header = true)
        repeat(3) { index ->
            val peak = display.peaks.getOrNull(index)
            PeakRow(
                first = (index + 1).toString(),
                second = peak?.let { format(it.omega, 2) } ?: "—",
                third = peak?.takeIf { it.omega > 0.0 }?.let { format(2.0 * PI / it.omega, 3) } ?: "—",
                fourth = peak?.let { format(it.amplitude, 3) } ?: "—",
            )
        }
    }
}

@Composable
private fun PeakRow(first: String, second: String, third: String, fourth: String, header: Boolean = false) {
    val weight = if (header) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().height(21.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(first, Modifier.weight(0.55f), fontSize = 12.sp, fontWeight = weight)
        Text(second, Modifier.weight(1.4f), fontSize = 12.sp, fontWeight = weight)
        Text(third, Modifier.weight(0.85f), fontSize = 12.sp, fontWeight = weight)
        Text(fourth, Modifier.weight(0.8f), fontSize = 12.sp, fontWeight = weight)
    }
}

@Composable
private fun WindowEditor(
    currentSeconds: Double,
    onValidValue: (Double) -> Unit,
    sessionState: SessionState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(formatWindow(currentSeconds)) }
    val parsed = text.toDoubleOrNull()
    val invalid = parsed == null || parsed !in 1.0..60.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FFT window:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = text,
            onValueChange = { candidate ->
                if (candidate.length <= 5 && candidate.all { it.isDigit() || it == '.' } && candidate.count { it == '.' } <= 1) {
                    text = candidate
                    candidate.toDoubleOrNull()?.takeIf { it in 1.0..60.0 }?.let(onValidValue)
                }
            },
            modifier = Modifier.width(58.dp).height(48.dp),
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Text("s", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onStart,
            enabled = sessionState != SessionState.RUNNING,
            modifier = Modifier.width(48.dp).height(42.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        ) { Text("Start", fontSize = 10.sp) }
        OutlinedButton(
            onClick = onPause,
            enabled = sessionState == SessionState.RUNNING,
            modifier = Modifier.width(50.dp).height(42.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        ) { Text("Pause", fontSize = 10.sp) }
        OutlinedButton(
            onClick = onEnd,
            enabled = sessionState != SessionState.END,
            modifier = Modifier.width(43.dp).height(42.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        ) { Text("End", fontSize = 10.sp) }
    }
    Text(
        if (invalid) "Enter 1–60 s. Longer windows improve low-frequency resolution."
        else "Δf ≈ ${format(1.0 / parsed!!, 3)} Hz. Longer windows improve low-frequency resolution.",
        color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        lineHeight = 12.sp,
    )
}

@Composable
private fun RawValues(state: FftUiState) {
    val sample = state.latest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            RawRow("ax", sample?.ax, "ay", sample?.ay)
            RawRow("az", sample?.az, "atotal", sample?.total)
            Row(Modifier.fillMaxWidth()) {
                RawText("t", state.elapsedSeconds, Modifier.weight(1f), unit = "s")
                Text(
                    state.sensorStatus,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RawRow(firstName: String, first: Double?, secondName: String, second: Double?) {
    Row(Modifier.fillMaxWidth()) {
        RawText(firstName, first, Modifier.weight(1f))
        RawText(secondName, second, Modifier.weight(1f))
    }
}

@Composable
private fun RawText(name: String, value: Double?, modifier: Modifier, unit: String = "m/s²") {
    Text(
        text = "$name = ${value?.let { format(it, 3) } ?: "—"} $unit",
        modifier = modifier,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun AxisSelector(selected: SignalAxis, onSelect: (SignalAxis) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SignalAxis.entries.forEach { axis ->
            FilterChip(
                selected = axis == selected,
                onClick = { onSelect(axis) },
                label = { Text(axis.label, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun formatWindow(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else format(value, 1)

private fun chartStatus(state: FftUiState): String = when (state.sessionState) {
    SessionState.END -> "End • press Start to begin"
    SessionState.PAUSED -> "Paused • t ${format(state.elapsedSeconds, 2)} s"
    SessionState.RUNNING -> if (state.display.collecting) {
        "Collecting ${formatWindow(state.windowSeconds)} s window…"
    } else {
        "${state.selectedAxis.label}  •  fs ${format(state.display.samplingRateHz ?: 0.0, 1)} Hz"
    }
}
