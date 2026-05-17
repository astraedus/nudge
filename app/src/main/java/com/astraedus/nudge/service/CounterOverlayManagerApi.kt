package com.astraedus.nudge.service

/** Thin interface extracted for testability. */
interface CounterOverlayManagerApi {
    fun isVisible(): Boolean
    fun show(label: String = "taps")
    fun updateCount(sessionCount: Int, dailyTotal: Int)
    fun hide()
}
