package com.astraedus.nudge.service

/** Thin interface extracted for testability. */
interface TimeRemainingHandlerApi {
    fun maybeUpdate(packageName: String)
    fun resetDebounce()
    fun hide()
}
