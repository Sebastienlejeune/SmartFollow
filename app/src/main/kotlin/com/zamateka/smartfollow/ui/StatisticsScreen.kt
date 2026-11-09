package com.zamateka.smartfollow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.history.ReadingStore
import com.zamateka.smartfollow.model.GlucoseStatistics
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private const val DAY_MILLIS = 24 * 60 * 60_000L

enum class StatisticsPeriod(val days: Int, @param:StringRes val label: Int) {
    DAY(1, R.string.period_day),
    WEEK(7, R.string.period_week),
    TWO_WEEKS(14, R.string.period_two_weeks),
    MONTH(30, R.string.period_month),
    QUARTER(90, R.string.period_quarter),
}

/** Colours of the consensus ranges, as in Dexcom Clarity. */
private val VeryHighColor = Color(0xFFFB8C00)
private val HighRangeColor = Color(0xFFFDD835)
private val InRangeColor = Color(0xFF43A047)
private val LowRangeColor = Color(0xFFE53935)
private val VeryLowColor = Color(0xFFB71C1C)

/**
 * Time in range and the other standard CGM metrics over the chosen period, computed from the
 * readings kept on the phone. [refreshKey] reloads them, for example at each new download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(readingStore: ReadingStore, refreshKey: Any?) {
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.TWO_WEEKS) }
    val statistics by produceState<GlucoseStatistics?>(null, period, refreshKey) {
        val now = System.currentTimeMillis()
        val from = now - period.days * DAY_MILLIS
        value = GlucoseStatistics.compute(readingStore.since(from), from, now)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StatisticsPeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == period,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, StatisticsPeriod.entries.size),
                    icon = {},
                ) { Text(stringResource(entry.label)) }
            }
        }

        val stats = statistics
        if (stats == null) {
            Text(stringResource(R.string.stats_no_data))
        } else {
            TimeInRanges(stats)
            GlucoseMetrics(stats)
            DataQuality(stats)
        }
        Text(
            pluralStringResource(R.plurals.stats_note, ReadingStore.RETENTION_YEARS, ReadingStore.RETENTION_YEARS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        CsvExport(readingStore)
    }
}

/** Saves every kept reading as a CSV file, where the user chooses (Drive, Downloads...). */
@Composable
private fun CsvExport(readingStore: ReadingStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = runCatching {
                val output = context.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
                readingStore.exportCsv(output)
            }.fold(
                onSuccess = { resources.getQuantityString(R.plurals.export_done, it, it) },
                onFailure = { resources.getString(R.string.export_failed, it.message.orEmpty()) },
            )
        }
    }
    OutlinedButton(onClick = { launcher.launch("smartfollow-readings-${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_csv))
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun TimeInRanges(stats: GlucoseStatistics) {
    val ranges = listOf(
        RangeLine(VeryHighColor, R.string.range_very_high, "> ${GlucoseStatistics.VERY_HIGH_ABOVE}", stats.veryHigh, R.string.target_very_high),
        RangeLine(HighRangeColor, R.string.range_high, "${GlucoseStatistics.HIGH_ABOVE + 1}–${GlucoseStatistics.VERY_HIGH_ABOVE}", stats.high, R.string.target_high),
        RangeLine(InRangeColor, R.string.range_in, "${GlucoseStatistics.LOW_BELOW}–${GlucoseStatistics.HIGH_ABOVE}", stats.inRange, R.string.target_in_range),
        RangeLine(LowRangeColor, R.string.range_low, "${GlucoseStatistics.VERY_LOW_BELOW}–${GlucoseStatistics.LOW_BELOW - 1}", stats.low, R.string.target_low),
        RangeLine(VeryLowColor, R.string.range_very_low, "< ${GlucoseStatistics.VERY_LOW_BELOW}", stats.veryLow, R.string.target_very_low),
    )
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.stats_time_in_ranges), style = MaterialTheme.typography.titleMedium)
            // Stacked bar, from very low at the bottom to very high at the top, as in the usual reports.
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                var left = 0f
                ranges.asReversed().forEach { range ->
                    val width = size.width * range.share.toFloat()
                    drawRect(range.color, topLeft = Offset(left, 0f), size = Size(width, size.height))
                    left += width
                }
            }
            ranges.forEach { RangeRow(it) }
        }
    }
}

private class RangeLine(
    val color: Color,
    @param:StringRes val label: Int,
    val bounds: String,
    val share: Double,
    @param:StringRes val target: Int,
)

@Composable
private fun RangeRow(range: RangeLine) {
    val minutesPerDay = (range.share * 24 * 60).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).background(range.color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text("${stringResource(range.label)} (${range.bounds})", style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(range.target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(percent(range.share), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.stats_per_day, minutesPerDay / 60, minutesPerDay % 60),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun GlucoseMetrics(stats: GlucoseStatistics) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_glucose), style = MaterialTheme.typography.titleMedium)
            MetricRow(stringResource(R.string.stats_mean), "${stats.meanMgDl.toInt()} mg/dL")
            MetricRow(stringResource(R.string.stats_gmi), String.format(Locale.getDefault(), "%.1f %%", stats.gmiPercent))
            MetricRow(
                stringResource(R.string.stats_cv),
                String.format(Locale.getDefault(), "%.0f %%", stats.coefficientOfVariationPercent),
                stringResource(R.string.target_cv),
            )
            MetricRow(stringResource(R.string.stats_sd), "${stats.standardDeviationMgDl.toInt()} mg/dL")
        }
    }
}

@Composable
private fun DataQuality(stats: GlucoseStatistics) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_data), style = MaterialTheme.typography.titleMedium)
            MetricRow(stringResource(R.string.stats_coverage), percent(stats.coverage), stringResource(R.string.target_coverage))
            MetricRow(stringResource(R.string.stats_readings), stats.readingCount.toString())
            MetricRow(
                stringResource(R.string.stats_since),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(stats.firstReadingMillis)),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun percent(share: Double): String = String.format(Locale.getDefault(), "%.0f %%", share * 100)
