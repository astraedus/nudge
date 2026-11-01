package com.astraedus.nudge.domain.logging

interface NudgeLog {
    fun d(message: String, throwable: Throwable? = null)
    fun i(message: String, throwable: Throwable? = null)
    fun w(message: String, throwable: Throwable? = null)
    fun e(message: String, throwable: Throwable? = null)

    /**
     * A view of this log whose lines carry [tag] in logcat. Every class that logs calls this once
     * with a string LITERAL; the tag is never derived from the class at runtime, because R8 merges
     * and inlines classes in release builds and a derived tag then comes out as `ResultKt` (#120).
     * Implementations with no logcat output (the no-op, test recorders) keep the default.
     */
    fun tagged(tag: String): NudgeLog = this

    object NoOp : NudgeLog {
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun i(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
    }
}
