package io.github.kesemb2.calibrate

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app is always in Hebrew, right to left, whatever the box's language (the user's choice). English strings stay in
 * res/values as the fallback, so switching back is a one-line change here.
 */
object AppLocale {
    val locale: Locale = Locale.forLanguageTag("he-IL")

    fun wrap(base: Context): Context {
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
