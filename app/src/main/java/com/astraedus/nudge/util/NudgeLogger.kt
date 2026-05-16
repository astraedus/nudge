package com.astraedus.nudge.util

import android.util.Log
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NudgeLogger @Inject constructor(
    preferences: NudgePreferences
) : NudgeLog {

    @Volatile
    private var debugLoggingPreferenceEnabled = false

    val isDebugEnabled: Boolean
        get() = BuildConfig.DEBUG || debugLoggingPreferenceEnabled

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            preferences.isDebugLoggingEnabled.collectLatest { enabled ->
                debugLoggingPreferenceEnabled = enabled
            }
        }
    }

    override fun d(message: String, throwable: Throwable?) {
        d(message = message, throwable = throwable, tag = inferTag())
    }

    override fun i(message: String, throwable: Throwable?) {
        i(message = message, throwable = throwable, tag = inferTag())
    }

    override fun w(message: String, throwable: Throwable?) {
        w(message = message, throwable = throwable, tag = inferTag())
    }

    override fun e(message: String, throwable: Throwable?) {
        e(message = message, throwable = throwable, tag = inferTag())
    }

    fun d(message: String, throwable: Throwable? = null, tag: String = inferTag()) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
    }

    fun i(message: String, throwable: Throwable? = null, tag: String = inferTag()) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
    }

    fun w(message: String, throwable: Throwable? = null, tag: String = inferTag()) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = inferTag()) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    private fun inferTag(): String {
        return Throwable().stackTrace
            .firstOrNull { frame ->
                frame.className != NudgeLogger::class.java.name &&
                    !frame.className.startsWith("com.astraedus.nudge.util.NudgeLogger")
            }
            ?.className
            ?.substringAfterLast('.')
            ?.take(23)
            ?: "Nudge"
    }
}
