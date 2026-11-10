package com.zamateka.smartfollow.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.arrow
import com.zamateka.smartfollow.share.DexcomShareException
import java.util.Locale

/** The app's locale, read so that a language change recomposes (unlike Locale.getDefault()). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

fun glucoseText(context: Context, reading: GlucoseReading): String =
    context.getString(R.string.glucose_value, reading.mgDl, reading.trend.arrow()).trim()

@StringRes
fun AlarmType.labelRes(): Int = when (this) {
    AlarmType.URGENT_LOW -> R.string.alarm_urgent_low
    AlarmType.FAST_DROP -> R.string.alarm_fast_drop
    AlarmType.NO_RECOVERY -> R.string.alarm_no_recovery
    AlarmType.LOW -> R.string.alarm_low
    AlarmType.LOW_SOON -> R.string.alarm_low_soon
    AlarmType.STALE_DATA -> R.string.alarm_stale_data
    AlarmType.URGENT_HIGH -> R.string.alarm_urgent_high
    AlarmType.HIGH -> R.string.alarm_high
}

@StringRes
fun AlarmType.descriptionRes(): Int = when (this) {
    AlarmType.URGENT_LOW -> R.string.alarm_description_urgent_low
    AlarmType.FAST_DROP -> R.string.alarm_description_fast_drop
    AlarmType.NO_RECOVERY -> R.string.alarm_description_no_recovery
    AlarmType.LOW -> R.string.alarm_description_low
    AlarmType.LOW_SOON -> R.string.alarm_description_low_soon
    AlarmType.STALE_DATA -> R.string.alarm_description_stale_data
    AlarmType.URGENT_HIGH -> R.string.alarm_description_urgent_high
    AlarmType.HIGH -> R.string.alarm_description_high
}

/** Label of the device an action was made on, or `null` for an unknown source. */
@StringRes
fun sourceLabelRes(source: String): Int? = when (source) {
    "android" -> R.string.source_android
    "web" -> R.string.source_web
    "ntfy" -> R.string.source_ntfy
    else -> null
}

private val OutOfRangeColor = Color(0xFFE53935)
private val NearRangeColor = Color(0xFFFB8C00)
private val InRangeColor = Color(0xFF43A047)
/**
 * Colour of a glucose value, symmetric for lows and highs: green in the target range
 * (low threshold to the top of the target), orange up to the urgent low or high threshold, red beyond.
 */
fun glucoseColor(mgDl: Int, settings: AlarmSettings = AlarmSettings()): Color {
    val high = settings.highMgDl
    return when {
        mgDl < settings.urgentLowMgDl -> OutOfRangeColor
        mgDl < settings.lowMgDl -> NearRangeColor
        mgDl <= settings.targetHighMgDl -> InRangeColor
        high == null || mgDl <= high -> NearRangeColor
        else -> OutOfRangeColor
    }
}

@Composable
fun errorText(error: Throwable): String = when (error) {
    is DexcomShareException.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    is DexcomShareException.TooManyAttempts -> stringResource(R.string.error_account_locked)
    is DexcomShareException.ServerError -> stringResource(R.string.error_server, error.code ?: error.message.orEmpty())
    is DexcomShareException.SessionExpired -> stringResource(R.string.error_server, error.message.orEmpty())
    else -> stringResource(R.string.error_network, error.message ?: error::class.simpleName.orEmpty())
}
