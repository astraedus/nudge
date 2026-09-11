package com.astraedus.nudge.service

/** Thin interface extracted for testability. */
interface CounterOverlayManagerApi {
    fun isVisible(): Boolean
    fun show(label: String = "taps")
    fun updateCount(sessionCount: Int, dailyTotal: Int)

    /**
     * Change the caption of an already-visible overlay.
     *
     * Needed because the caption can change WHILE the overlay is up: the first item consumed
     * promotes a tap-counting session to items and resets the number, so an overlay that only set
     * its caption in [show] read "5 taps" and then "1 taps" -- a stale word over a new unit, which
     * is the same lie the mode rule exists to prevent, showing up in the one place the user actually
     * looks.
     */
    fun updateLabel(label: String)

    fun hide()
}
