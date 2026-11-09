package com.zamateka.smartfollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.model.GlucoseReading
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** Time span shown by [GlucoseChart]. */
enum class ChartRange(val hours: Int, val pointRadius: Dp, /** Time between two labelled clock times. */ val tickMinutes: Int) {
    ONE_HOUR(1, 5.dp, 15),
    THREE_HOURS(3, 4.dp, 60),
    SIX_HOURS(6, 3.dp, 120),
    TWELVE_HOURS(12, 2.dp, 180),
    DAY(24, 1.5.dp, 360),
    ;

    val millis: Long get() = hours * 60 * 60_000L
}

private const val MIN_Y_MG_DL = 40f
private const val DEFAULT_MAX_Y_MG_DL = 320f

/** Width of the left column holding the threshold values. */
private val THRESHOLD_GUTTER = 28.dp

/**
 * Round clock times within ([fromMillis], [toMillis]], every [stepMinutes] from midnight in
 * [zone] (a multiple of 15): the grid lines and time labels of the chart. Mirrored by the web page.
 */
internal fun clockTicks(fromMillis: Long, toMillis: Long, stepMinutes: Int, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val ticks = mutableListOf<Long>()
    var time = Instant.ofEpochMilli(fromMillis).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    while (time.toInstant().toEpochMilli() <= toMillis) {
        val millis = time.toInstant().toEpochMilli()
        if (millis > fromMillis && (time.hour * 60 + time.minute) % stepMinutes == 0) ticks += millis
        time = time.plusMinutes(15)
    }
    return ticks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartRangeSelector(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        ChartRange.entries.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index, ChartRange.entries.size),
                icon = {},
            ) { Text(stringResource(R.string.hours_value, range.hours)) }
        }
    }
}

/**
 * Glucose over [range], with the target range shaded, the alarm thresholds and the top of the
 * target dashed and labelled with their value. Touching the chart shows the reading under the finger, which follows it.
 */
@Composable
fun GlucoseChart(
    readings: List<GlucoseReading>,
    nowMillis: Long,
    range: ChartRange,
    settings: AlarmSettings,
    /** Resugarings, drawn as vertical lines. */
    resugaringTimesMillis: List<Long>,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val targetBand = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val resugaringColor = MaterialTheme.colorScheme.tertiary
    val textMeasurer = rememberTextMeasurer()
    val thresholdStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val cursorStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onSurface)
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val windowMillis = range.millis
    val visible = readings.filter { it.timestampMillis >= nowMillis - windowMillis }
    val maxY = max(DEFAULT_MAX_Y_MG_DL, (visible.maxOfOrNull { it.mgDl } ?: 0) + 20f)
    var touchX by remember { mutableStateOf<Float?>(null) }
    val ticks = clockTicks(nowMillis - windowMillis, nowMillis, range.tickMinutes)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = outline)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                // The gesture is consumed, so that dragging on the chart does not swipe the pages.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        touchX = down.position.x
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            touchX = change.position.x
                            change.consume()
                        }
                        touchX = null
                    }
                },
        ) {
            // The left gutter holds the threshold values, so that they never cover the readings.
            val gutter = THRESHOLD_GUTTER.toPx()
            val plotWidth = size.width - gutter
            fun x(t: Long) = gutter + plotWidth * (1f - (nowMillis - t).toFloat() / windowMillis)
            fun y(mgDl: Float) = size.height * (1f - (mgDl.coerceIn(MIN_Y_MG_DL, maxY) - MIN_Y_MG_DL) / (maxY - MIN_Y_MG_DL))
            val dashed = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))

            drawRect(
                color = targetBand,
                topLeft = Offset(gutter, y(settings.targetHighMgDl.toFloat())),
                size = Size(plotWidth, y(settings.lowMgDl.toFloat()) - y(settings.targetHighMgDl.toFloat())),
            )
            ticks.forEach {
                val gridX = x(it)
                drawLine(outline.copy(alpha = 0.3f), Offset(gridX, 0f), Offset(gridX, size.height))
            }
            // From the top: each label is centred on its line, pushed down if it would overlap the previous one.
            var previousLabelBottom = Float.NEGATIVE_INFINITY
            listOfNotNull(settings.urgentLowMgDl, settings.lowMgDl, settings.targetHighMgDl, settings.highMgDl, settings.urgentHighMgDl)
                .sortedDescending()
                .forEach { threshold ->
                    val lineY = y(threshold.toFloat())
                    // Low thresholds take the colour of the zone below, high ones of the zone above.
                    val zoneValue = if (threshold <= settings.lowMgDl) threshold - 1 else threshold + 1
                    val color = glucoseColor(zoneValue, settings)
                    drawLine(color, Offset(gutter, lineY), Offset(size.width, lineY), pathEffect = dashed)
                    val label = textMeasurer.measure(threshold.toString(), thresholdStyle.copy(color = color))
                    val top = maxOf(lineY - label.size.height / 2f, previousLabelBottom)
                        .coerceIn(0f, size.height - label.size.height)
                    drawText(label, topLeft = Offset(gutter - label.size.width - 4.dp.toPx(), top))
                    previousLabelBottom = top + label.size.height
                }
            resugaringTimesMillis.filter { it >= nowMillis - windowMillis }.forEach {
                val markX = x(it)
                drawLine(resugaringColor, Offset(markX, 0f), Offset(markX, size.height), strokeWidth = 2.dp.toPx())
            }
            val radius = range.pointRadius.toPx()
            visible.forEach {
                val center = Offset(x(it.timestampMillis), y(it.mgDl.toFloat()))
                drawCircle(color = glucoseColor(it.mgDl, settings), radius = radius, center = center)
            }

            // Cursor: the reading closest to the finger, with its value and time.
            val touched = touchX?.let { tx -> visible.minByOrNull { abs(x(it.timestampMillis) - tx) } }
            if (touched != null) {
                val cursorX = x(touched.timestampMillis)
                val cursorY = y(touched.mgDl.toFloat())
                drawLine(onSurface.copy(alpha = 0.6f), Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1.5.dp.toPx())
                drawCircle(onSurface, radius = radius + 3.dp.toPx(), center = Offset(cursorX, cursorY), style = Stroke(2.dp.toPx()))
                val text = "${touched.mgDl} mg/dL · ${timeFormat.format(Date(touched.timestampMillis))}"
                val layout = textMeasurer.measure(text, cursorStyle)
                val padding = 6.dp.toPx()
                val boxWidth = layout.size.width + 2 * padding
                val boxHeight = layout.size.height + 2 * padding
                val boxLeft = (cursorX - boxWidth / 2).coerceIn(gutter, size.width - boxWidth)
                drawRoundRect(surface, topLeft = Offset(boxLeft, 0f), size = Size(boxWidth, boxHeight), cornerRadius = CornerRadius(padding))
                drawText(layout, topLeft = Offset(boxLeft + padding, padding))
            }
        }
        // The clock time of each grid line, centred under it but kept within the chart.
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val gutter = THRESHOLD_GUTTER.toPx()
            val plotWidth = size.width - gutter
            ticks.forEach {
                val layout = textMeasurer.measure(timeFormat.format(Date(it)), labelStyle)
                val center = gutter + plotWidth * (1f - (nowMillis - it).toFloat() / windowMillis)
                val left = (center - layout.size.width / 2f).coerceIn(gutter, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(left, (size.height - layout.size.height) / 2f))
            }
        }
    }
}
