package com.astraedus.nudge.service

/** Thin interface extracted for testability. */
interface TimeRemainingOverlayManagerApi {
    fun show()
    fun hide()
    fun isVisible(): Boolean
    fun updateTimeRemaining(remainingMs: Long?, limitMinutes: Int?)
}
