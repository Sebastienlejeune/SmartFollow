package com.zamateka.smartfollow.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Language of the app, chosen in the settings: the system language (default), French or English.
 *
 * Uses the per-app language of Android 13+ (the same choice as in Android's settings, Apps ›
 * SmartFollow › Language), which applies to the whole app: screens and notifications. Below
 * Android 13 the choice is not offered and the app follows the system language.
 */
object AppLanguage {
    /** The languages of the app, as BCP 47 tags; each is shown in its own language. */
    val TAGS = listOf("fr", "en")

    /** Display name of a language in itself ("Français", "English"). */
    val NAMES = mapOf("fr" to "Français", "en" to "English")

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen language, or null when the app follows the system language. */
    fun current(context: Context): String? {
        if (!isSupported()) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0].language.takeIf { it in TAGS }
    }

    /** Sets the language (null: the system language); Android recreates the visible activity. */
    fun set(context: Context, tag: String?) {
        if (!isSupported()) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
