package com.zamateka.smartfollow.alarm

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class AlarmSettings(
    val urgentLowMgDl: Int = 55,
    val lowMgDl: Int = 70,
    /** Top of the target range, which starts at [lowMgDl]. Display only: no alarm uses it. */
    val targetHighMgDl: Int = 180,
    /** `null` disables the high alarm. */
    val highMgDl: Int? = 250,
    /** `null` disables the urgent high alarm. */
    val urgentHighMgDl: Int? = 300,
    /** A zone is entered when crossing its threshold and left only once glucose is this far past it. */
    val hysteresisMgDl: Int = 5,
    /** How long the low alarm stays silent after a resugaring is logged. */
    val resugaringSnooze: Duration = 25.minutes,
    /** Delay after a resugaring after which glucose is expected to have started rising. */
    val recoveryCheckDelay: Duration = 15.minutes,
    /** Drop rate (mg/dL per minute) that forces an alarm during a resugaring snooze. */
    val fastDropMgDlPerMin: Double = 2.0,
    /** How far ahead the glucose trend is extrapolated to raise [AlarmType.LOW_SOON]. */
    val lowSoonHorizon: Duration = 20.minutes,
    /** A reading older than this is not trusted, and its absence raises [AlarmType.STALE_DATA]. */
    val staleDataAfter: Duration = 15.minutes,
    /**
     * How long an alarm stays silent while its condition remains active: after "OK" for a
     * continuous alarm, after it rang for the others. Not used for [ONCE_PER_EPISODE] alarms.
     */
    val repeatIntervals: Map<AlarmType, Duration> = DEFAULT_REPEAT_INTERVALS,
    /** Alarms that never sound; their conditions are still reported. See [ALWAYS_ENABLED]. */
    val disabledAlarms: Set<AlarmType> = emptySet(),
    /**
     * Alarms that ring until acknowledged. The others ring once ([Evaluation.alert]), then
     * again after their repeat interval while their condition lasts.
     */
    val continuousAlarms: Set<AlarmType> = DEFAULT_CONTINUOUS_ALARMS,
) {
    init {
        require(urgentLowMgDl < lowMgDl) { "urgentLowMgDl must be below lowMgDl" }
        require(targetHighMgDl > lowMgDl) { "targetHighMgDl must be above lowMgDl" }
        require(highMgDl == null || highMgDl > lowMgDl) { "highMgDl must be above lowMgDl" }
        require(urgentHighMgDl == null || urgentHighMgDl > (highMgDl ?: lowMgDl)) { "urgentHighMgDl must be above highMgDl" }
        require(hysteresisMgDl >= 0) { "hysteresisMgDl must not be negative" }
        require(fastDropMgDlPerMin > 0) { "fastDropMgDlPerMin must be positive" }
        require(recoveryCheckDelay < resugaringSnooze) { "recoveryCheckDelay must be shorter than resugaringSnooze" }
        require(lowSoonHorizon.isPositive()) { "lowSoonHorizon must be positive" }
        require(AlarmType.entries.all { it in repeatIntervals }) { "repeatIntervals must cover every AlarmType" }
        require(disabledAlarms.none { it in ALWAYS_ENABLED }) { "$ALWAYS_ENABLED cannot be disabled" }
    }

    companion object {
        /** Safety alarms that cannot be disabled. */
        val ALWAYS_ENABLED: Set<AlarmType> = setOf(AlarmType.URGENT_LOW, AlarmType.STALE_DATA)

        /**
         * Alarms that stay silent until their condition ends once they rang or were acknowledged:
         * a prediction that lasts tells nothing new, and the low it predicts has its own alarm.
         */
        val ONCE_PER_EPISODE: Set<AlarmType> = setOf(AlarmType.LOW_SOON)

        val DEFAULT_CONTINUOUS_ALARMS: Set<AlarmType> = setOf(AlarmType.URGENT_LOW, AlarmType.URGENT_HIGH, AlarmType.HIGH)

        val DEFAULT_REPEAT_INTERVALS: Map<AlarmType, Duration> = mapOf(
            AlarmType.URGENT_LOW to 5.minutes,
            AlarmType.FAST_DROP to 5.minutes,
            AlarmType.NO_RECOVERY to 10.minutes,
            AlarmType.LOW to 15.minutes,
            AlarmType.LOW_SOON to 15.minutes,
            AlarmType.STALE_DATA to 20.minutes,
            AlarmType.URGENT_HIGH to 30.minutes,
            AlarmType.HIGH to 60.minutes,
        )
    }
}
