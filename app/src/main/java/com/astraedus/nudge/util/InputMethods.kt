package com.astraedus.nudge.util

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Every keyboard the user could have on screen: the default input method plus every ENABLED one.
 *
 * One helper because it has two callers that must agree: the Nuke safety floor (a keyboard can never
 * be nuked, or the emergency-exit text field becomes untypeable) and the event classifier's
 * transient set (a keyboard's window is never "the user left the app", issue
 * [#76](https://github.com/astraedus/nudge/issues/76)). The default alone was not enough for the
 * second: a user who switches keyboards from the IME picker, or an OEM that brings up its own
 * keyboard for one field, shows a keyboard that is enabled but not the default.
 *
 * `getEnabledInputMethodList` is API 3 and `DEFAULT_INPUT_METHOD` API 3, so nothing here needs a
 * version check at minSdk 26. Throws whatever the platform throws; callers decide how to fail.
 */
object InputMethods {

    fun enabledPackages(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { set += it }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.enabledInputMethodList
            ?.mapNotNullTo(set) { it.packageName?.takeIf(String::isNotBlank) }
        return set
    }
}
