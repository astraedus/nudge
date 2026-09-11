package com.astraedus.nudge.domain.widget

/**
 * "Something the home-screen widgets display just changed."
 *
 * Declared in `domain` and implemented in `ui/widget` for the same reason `service.UsageProvider`
 * and `service.GlobalEnabledProvider` are declared where they are: the dependency rule in this
 * module is `ui -> domain <- data`, so `data` may not import a Glance class. The consumer names the
 * capability it needs; the widget layer supplies it.
 *
 * Implementations MUST be non-suspending and fire-and-forget. The call site that matters is
 * `UsageRepository.logEvent`, which sits on the accessibility hot path — every block decision and
 * every walk-away passes through it, and it must not grow an IPC round-trip's worth of latency
 * because a widget wants a fresher number.
 */
fun interface WidgetRefreshSignal {

    fun requestRefresh()

    companion object {
        /**
         * The signal for a caller that has no widgets to push to.
         *
         * It exists so the two screens that build a `NudgePreferences` by hand (Settings and the
         * messages editor, which only READ preferences) keep compiling without pretending to own a
         * refresh path. It is deliberately a NAMED constant rather than a defaulted empty lambda:
         * a silent no-op that nobody can see is how a feature switches itself off, and this repo
         * has already paid for that lesson twice. If a future caller writes a widget-visible value
         * through one of those hand-built instances, it will show up as this constant in the diff.
         */
        val NONE: WidgetRefreshSignal = WidgetRefreshSignal { }
    }
}
