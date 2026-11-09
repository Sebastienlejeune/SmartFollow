package com.zamateka.smartfollow.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** History kept on the phone: monitoring events ([EventStore]) and glucose readings ([ReadingStore]). */
class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "history.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $EVENTS (" +
                "$EVENT_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$EVENT_TIMESTAMP INTEGER NOT NULL, " +
                "$EVENT_KIND TEXT NOT NULL, " +
                "$EVENT_ALARM_TYPE TEXT, " +
                "$EVENT_MG_DL INTEGER, " +
                "$EVENT_SOURCE TEXT, " +
                "$EVENT_PROFILE TEXT)",
        )
        db.execSQL("CREATE INDEX events_timestamp ON $EVENTS ($EVENT_TIMESTAMP)")
        createReadings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 records where a shared action came from.
        if (oldVersion < 2) db.execSQL("ALTER TABLE $EVENTS ADD COLUMN $EVENT_SOURCE TEXT")
        // Version 3 keeps the readings, for the statistics.
        if (oldVersion < 3) createReadings(db)
        // Version 4 names the profile activated.
        if (oldVersion < 4) db.execSQL("ALTER TABLE $EVENTS ADD COLUMN $EVENT_PROFILE TEXT")
    }

    private fun createReadings(db: SQLiteDatabase) {
        // One reading per timestamp: a reading downloaded again replaces the stored one.
        db.execSQL(
            "CREATE TABLE $READINGS (" +
                "$READING_TIMESTAMP INTEGER PRIMARY KEY, " +
                "$READING_MG_DL INTEGER NOT NULL, " +
                "$READING_TREND TEXT NOT NULL)",
        )
    }

    companion object {
        const val EVENTS = "events"
        const val EVENT_ID = "id"
        const val EVENT_TIMESTAMP = "timestamp"
        const val EVENT_KIND = "kind"
        const val EVENT_ALARM_TYPE = "alarm_type"
        const val EVENT_MG_DL = "mg_dl"
        const val EVENT_SOURCE = "source"
        const val EVENT_PROFILE = "profile"

        const val READINGS = "readings"
        const val READING_TIMESTAMP = "timestamp"
        const val READING_MG_DL = "mg_dl"
        const val READING_TREND = "trend"
    }
}
