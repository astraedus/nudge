package com.astraedus.nudge.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * How the redirect app (the one "better app" offered on every block screen) is stored: one string,
 * the package name, absent when none is chosen.
 *
 * Split out of [NudgePreferences] because that class is not JVM-constructible (an Android `Context`
 * and a DataStore delegate) while these two functions are the whole contract, and `Preferences` is
 * plain JVM. `RedirectAppPrefTest` drives them directly and through a real file-backed DataStore.
 *
 * DEVICE-LOCAL, like the Nuke list: a package name chosen on one phone may not exist on the next,
 * and the render-time re-check would only turn it into the empty state anyway. Not in the backup
 * format.
 */
internal object RedirectAppPref {

    val KEY = stringPreferencesKey("redirect_app_package")

    /** The saved package, or null. A blank value (never written by us) reads as none. */
    fun read(prefs: Preferences): String? = prefs[KEY]?.trim()?.takeIf { it.isNotEmpty() }

    /** Save [packageName], or clear the choice when it is null or blank. */
    fun write(prefs: MutablePreferences, packageName: String?) {
        val pkg = packageName?.trim()
        if (pkg.isNullOrEmpty()) prefs.remove(KEY) else prefs[KEY] = pkg
    }
}
