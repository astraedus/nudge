package com.astraedus.nudge.domain.logging

interface NudgeLog {
    fun d(message: String, throwable: Throwable? = null)
    fun i(message: String, throwable: Throwable? = null)
    fun w(message: String, throwable: Throwable? = null)
    fun e(message: String, throwable: Throwable? = null)

    object NoOp : NudgeLog {
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun i(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
    }
}
