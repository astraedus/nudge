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
        d(message = message, throwable = throwable, tag = DEFAULT_TAG)
    }

    override fun i(message: String, throwable: Throwable?) {
        i(message = message, throwable = throwable, tag = DEFAULT_TAG)
    }

    override fun w(message: String, throwable: Throwable?) {
        w(message = message, throwable = throwable, tag = DEFAULT_TAG)
    }

    override fun e(message: String, throwable: Throwable?) {
        e(message = message, throwable = throwable, tag = DEFAULT_TAG)
    }

    fun d(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
    }

    fun i(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
    }

    fun w(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (!isDebugEnabled) return
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    /**
     * The tag is always explicit. It used to be inferred from the calling class on the stack, which
     * R8 defeats in release builds: classes are merged and inlined, so every line came out as
     * `ResultKt` or `TuplesKt` (#120). Each logging class takes a [tagged] view with a literal.
     */
    override fun tagged(tag: String): NudgeLog = TaggedLog(this, tag)

    private class TaggedLog(private val root: NudgeLogger, private val tag: String) : NudgeLog {
        override fun d(message: String, throwable: Throwable?) = root.d(message, throwable, tag)
        override fun i(message: String, throwable: Throwable?) = root.i(message, throwable, tag)
        override fun w(message: String, throwable: Throwable?) = root.w(message, throwable, tag)
        override fun e(message: String, throwable: Throwable?) = root.e(message, throwable, tag)
        override fun tagged(tag: String): NudgeLog = TaggedLog(root, tag)
    }

    companion object {
        /** Tag for a line from a caller that did not take a [tagged] view. */
        const val DEFAULT_TAG = "Nudge"
    }
}
