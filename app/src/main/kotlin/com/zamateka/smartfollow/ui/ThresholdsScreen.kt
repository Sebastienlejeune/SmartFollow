package com.zamateka.smartfollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vertical scale of the thresholds ruler, 400 mg/dL at the top and 40 at the bottom, stretched
 * at the bottom so that the low thresholds, a few mg/dL apart, stay easy to set.
 */
internal object GlucoseScale {
    const val MIN_MG_DL = 40
    const val MAX_MG_DL = 400

    /** mg/dL and their height from the top, as a fraction; linear in between. */
    private val ANCHORS = listOf(400 to 0f, 200 to 0.36f, 100 to 0.62f, 40 to 1f)

    fun fraction(mgDl: Float): Float {
        val value = mgDl.coerceIn(MIN_MG_DL.toFloat(), MAX_MG_DL.toFloat())
        val (upper, lower) = ANCHORS.zipWithNext().first { (_, lower) -> value >= lower.first }
        return upper.second + (lower.second - upper.second) * (upper.first - value) / (upper.first - lower.first)
    }

    fun mgDl(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val (upper, lower) = ANCHORS.zipWithNext().first { (_, lower) -> f <= lower.second }
        return upper.first - (upper.first - lower.first) * (f - upper.second) / (lower.second - upper.second)
    }
}

/**
 * Tops of rows of [rowHeight] wanted centred at [centers] (top to bottom), pushed apart so that
 * they never overlap and stay within [total].
 */
internal fun spreadRows(centers: List<Float>, rowHeight: Float, total: Float): List<Float> {
    val tops = centers.map { it - rowHeight / 2 }.toMutableList()
    for (i in tops.indices) tops[i] = maxOf(tops[i], if (i == 0) 0f else tops[i - 1] + rowHeight)
    for (i in tops.indices.reversed()) tops[i] = minOf(tops[i], if (i == tops.lastIndex) total - rowHeight else tops[i + 1] - rowHeight)
    return tops
}

/** A limit of the ruler: its value, allowed values and how to change it. */
private class Limit(
    val label: String,
    val sub: String,
    val value: Int,
    val range: IntRange,
    val step: Int,
    val change: (AlarmPreferences, Int) -> AlarmPreferences,
)

private val RULER_HEIGHT = 540.dp
private val ROW_HEIGHT = 52.dp

/**
 * The glucose ranges: a coloured ruler whose limits are dragged, each with − and + buttons and
 * a value that can be typed. Used by the alarms, the chart and the colours.
 */
@Composable
fun ThresholdsScreen(profileName: String, preferences: AlarmPreferences, onChange: (AlarmPreferences) -> Unit, onBack: () -> Unit) {
    val current by rememberUpdatedState(preferences)
    val limits = limits(preferences)
    val currentLimits by rememberUpdatedState(limits)
    var typing by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf<Int?>(null) }

    typing?.let { index ->
        ValueDialog(limits[index], onDismiss = { typing = null }) { value ->
            onChange(limits[index].change(current, value))
            typing = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SettingsTopBar(stringResource(R.string.settings_thresholds_title), onBack, subtitle = stringResource(R.string.thresholds_subtitle, profileName))
        Text(
            stringResource(R.string.thresholds_hint),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BoxWithConstraints(Modifier.fillMaxWidth().height(RULER_HEIGHT).padding(horizontal = 16.dp)) {
            val density = LocalDensity.current
            val heightPx = with(density) { RULER_HEIGHT.toPx() }
            val settings = preferences.toAlarmSettings()
            val handleColor = MaterialTheme.colorScheme.onSurface
            val activeColor = MaterialTheme.colorScheme.primary
            val surface = MaterialTheme.colorScheme.surface
            val greenText = Color(0xFF2E7D32)

            Canvas(
                Modifier
                    .width(56.dp)
                    .height(RULER_HEIGHT)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                // The handle closest to the finger, within reach.
                                val closest = currentLimits.withIndex().minBy { abs(GlucoseScale.fraction(it.value.value.toFloat()) * heightPx - start.y) }
                                dragging = closest.index.takeIf { abs(GlucoseScale.fraction(closest.value.value.toFloat()) * heightPx - start.y) < 48.dp.toPx() }
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ ->
                            val index = dragging ?: return@detectDragGestures
                            change.consume()
                            val limit = currentLimits[index]
                            val raw = GlucoseScale.mgDl(change.position.y / heightPx)
                            val value = ((raw / limit.step).roundToInt() * limit.step).coerceIn(limit.range)
                            if (value != limit.value) onChange(limit.change(current, value))
                        }
                    },
            ) {
                val barLeft = 14.dp.toPx()
                val barWidth = 28.dp.toPx()
                val bounds = listOf(400, preferences.highMgDl, preferences.targetHighMgDl, preferences.lowMgDl, preferences.urgentLowMgDl, 40)
                val bar = Path().apply { addRoundRect(RoundRect(barLeft, 0f, barLeft + barWidth, size.height, CornerRadius(barWidth / 2))) }
                clipPath(bar) {
                    bounds.zipWithNext().forEach { (top, bottom) ->
                        val y0 = GlucoseScale.fraction(top.toFloat()) * size.height
                        val y1 = GlucoseScale.fraction(bottom.toFloat()) * size.height
                        drawRect(glucoseColor((top + bottom) / 2, settings), Offset(barLeft, y0), Size(barWidth, y1 - y0))
                    }
                }
                val urgentHighY = GlucoseScale.fraction(preferences.urgentHighMgDl.toFloat()) * size.height
                drawLine(Color.White, Offset(0f, urgentHighY), Offset(size.width, urgentHighY), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                limits.forEachIndexed { index, limit ->
                    val center = Offset(barLeft + barWidth / 2, GlucoseScale.fraction(limit.value.toFloat()) * size.height)
                    if (index == dragging) {
                        drawCircle(activeColor.copy(alpha = 0.18f), radius = 26.dp.toPx(), center = center)
                        drawCircle(activeColor, radius = 16.dp.toPx(), center = center)
                        drawCircle(surface, radius = 16.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                    } else {
                        drawCircle(surface, radius = 14.dp.toPx(), center = center)
                        drawCircle(handleColor, radius = 14.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                    }
                }
            }

            val inRangeCenter = (GlucoseScale.fraction(preferences.targetHighMgDl.toFloat()) + GlucoseScale.fraction(preferences.lowMgDl.toFloat())) / 2 * heightPx
            val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
            val tops = spreadRows(limits.map { GlucoseScale.fraction(it.value.toFloat()) * heightPx }, rowHeightPx, heightPx)
            // "In range" between the two limits of the target, unless a row covers it.
            if (tops.none { inRangeCenter in it..(it + rowHeightPx) }) {
                Text(
                    stringResource(R.string.thresholds_in_range),
                    Modifier.offset(x = 76.dp, y = with(density) { inRangeCenter.toDp() } - 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = greenText,
                )
            }
            limits.forEachIndexed { index, limit ->
                LimitRow(
                    limit = limit,
                    active = index == dragging,
                    onStep = { delta -> onChange(limit.change(current, (limit.value + delta * limit.step).coerceIn(limit.range))) },
                    onType = { typing = index },
                    modifier = Modifier.offset(x = 68.dp, y = with(density) { tops[index].toDp() }).width(maxWidth - 68.dp),
                )
            }
        }

        Text(
            stringResource(R.string.thresholds_footnote),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The five limits, top to bottom, each kept strictly inside its neighbours. */
@Composable
private fun limits(preferences: AlarmPreferences): List<Limit> {
    val step = AlarmPreferences.THRESHOLD_STEP
    val highStep = AlarmPreferences.HIGH_STEP
    val above = stringResource(R.string.thresholds_above)
    val upTo = stringResource(R.string.thresholds_up_to)
    val below = stringResource(R.string.thresholds_below)
    return listOf(
        Limit(
            stringResource(R.string.alarm_urgent_high), above, preferences.urgentHighMgDl,
            maxOf(AlarmPreferences.URGENT_HIGH_RANGE.first, preferences.highMgDl + highStep)..AlarmPreferences.URGENT_HIGH_RANGE.last, highStep,
        ) { p, v -> p.copy(urgentHighMgDl = v) },
        Limit(
            stringResource(R.string.alarm_high), above, preferences.highMgDl,
            maxOf(AlarmPreferences.HIGH_RANGE.first, preferences.targetHighMgDl + highStep)..minOf(AlarmPreferences.HIGH_RANGE.last, preferences.urgentHighMgDl - highStep),
            highStep,
        ) { p, v -> p.copy(highMgDl = v) },
        Limit(
            stringResource(R.string.settings_target_high), upTo, preferences.targetHighMgDl,
            maxOf(AlarmPreferences.TARGET_HIGH_RANGE.first, preferences.lowMgDl + highStep)..minOf(AlarmPreferences.TARGET_HIGH_RANGE.last, preferences.highMgDl - highStep),
            highStep,
        ) { p, v -> p.copy(targetHighMgDl = v) },
        Limit(
            stringResource(R.string.alarm_low), below, preferences.lowMgDl,
            maxOf(AlarmPreferences.LOW_RANGE.first, preferences.urgentLowMgDl + step)..minOf(AlarmPreferences.LOW_RANGE.last, preferences.targetHighMgDl - highStep),
            step,
        ) { p, v -> p.copy(lowMgDl = v) },
        Limit(
            stringResource(R.string.alarm_urgent_low), below, preferences.urgentLowMgDl,
            AlarmPreferences.URGENT_LOW_RANGE.first..minOf(AlarmPreferences.URGENT_LOW_RANGE.last, preferences.lowMgDl - step),
            step,
        ) { p, v -> p.copy(urgentLowMgDl = v) },
    )
}

@Composable
private fun LimitRow(limit: Limit, active: Boolean, onStep: (Int) -> Unit, onType: () -> Unit, modifier: Modifier) {
    Row(modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(limit.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(limit.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(
            onClick = { onStep(-1) },
            enabled = limit.value - limit.step >= limit.range.first,
            modifier = Modifier.size(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Box(
            Modifier
                .width(60.dp)
                .height(40.dp)
                .border(
                    width = if (active) 2.dp else 1.dp,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onType, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(
                    limit.value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        FilledTonalButton(
            onClick = { onStep(1) },
            enabled = limit.value + limit.step <= limit.range.last,
            modifier = Modifier.size(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Types a limit; refuses a value outside its allowed range. */
@Composable
private fun ValueDialog(limit: Limit, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(limit.value.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in limit.range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thresholds_value_title, limit.label)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.thresholds_value_range, limit.range.first, limit.range.last)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.acknowledge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
