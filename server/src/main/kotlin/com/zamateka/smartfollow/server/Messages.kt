package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.monitor.NightSummary
import com.zamateka.smartfollow.monitor.UserAction
import kotlin.math.roundToInt

enum class Language {
    EN,
    FR,
    ;

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.name.equals(code, ignoreCase = true) } ?: EN
    }
}

/** Texts of the notifications, in the language of the people receiving them. */
class Messages(private val language: Language) {
    private fun text(en: String, fr: String) = if (language == Language.FR) fr else en

    fun alarmTitle(type: AlarmType): String = when (type) {
        AlarmType.URGENT_LOW -> text("Urgent low", "Hypo sévère")
        AlarmType.FAST_DROP -> text("Dropping fast", "Chute rapide")
        AlarmType.NO_RECOVERY -> text("Not rising after resugaring", "Pas de remontée après resucrage")
        AlarmType.LOW -> text("Low", "Hypo")
        AlarmType.LOW_SOON -> text("Low soon", "Hypo imminente")
        AlarmType.STALE_DATA -> text("No data", "Pas de données")
        AlarmType.URGENT_HIGH -> text("Urgent high", "Hyper sévère")
        AlarmType.HIGH -> text("High", "Hyper")
    }

    fun noRecentData() = text("No recent data", "Pas de donnée récente")

    fun checkWearerPhone() = text(
        "Check the wearer's phone: on, charged, Bluetooth and network on.",
        "Vérifier le téléphone du porteur : allumé, chargé, Bluetooth et réseau actifs.",
    )

    fun nightSummaryTitle(from: String, to: String) = text("Night summary ($from–$to)", "Résumé de la nuit ($from–$to)")

    /** The body of the night summary, one line per topic. */
    fun nightSummaryBody(summary: NightSummary): String = buildList {
        if (summary.readingCount == 0) {
            add(text("No reading received during the night.", "Aucune mesure reçue cette nuit."))
        } else {
            add(
                text("In target: ", "Dans la cible : ") + "${(summary.inTarget * 100).roundToInt()} %" +
                    text(" · below ${summary.lowMgDl}: ", " · sous ${summary.lowMgDl} : ") + duration(summary.belowMinutes) +
                    text(" · above ${summary.targetHighMgDl}: ", " · au-dessus de ${summary.targetHighMgDl} : ") + duration(summary.aboveMinutes),
            )
            add(text("Min ${summary.minMgDl} · mean ${summary.meanMgDl} · max ${summary.maxMgDl} mg/dL", "Min ${summary.minMgDl} · moyenne ${summary.meanMgDl} · max ${summary.maxMgDl} mg/dL"))
        }
        add(
            count(summary.alarms, text("alarm", "alarme"), text("alarms", "alarmes")) + " · " +
                count(summary.resugarings, text("resugaring", "resucrage"), text("resugarings", "resucrages")),
        )
    }.joinToString("\n")

    private fun duration(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"

    /** French uses the singular for zero too. */
    private fun count(value: Int, one: String, many: String): String {
        val singular = value == 1 || (value == 0 && language == Language.FR)
        return "$value ${if (singular) one else many}"
    }

    fun resugaringButton() = text("Resugaring done", "Resucrage effectué")

    fun acknowledgeButton() = "OK"

    fun actionTitle(action: UserAction): String = when (action) {
        UserAction.RESUGARING -> text("Resugaring logged", "Resucrage enregistré")
        UserAction.RESUGARING_CANCELLED -> text("Resugaring cancelled", "Resucrage annulé")
        UserAction.ACKNOWLEDGED -> text("Alarm acknowledged", "Alarme acquittée")
    }

    /** For example "at 02:11, from the Android app". */
    fun actionBody(time: String, source: String): String = text("at $time, from ${sourceLabel(source)}", "à $time, depuis ${sourceLabel(source)}")

    private fun sourceLabel(source: String): String = when (source) {
        "android" -> text("the Android app", "l'app Android")
        "web" -> text("the web page", "la page web")
        "ntfy" -> text("a notification", "une notification")
        else -> source
    }
}
