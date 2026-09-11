package com.astraedus.nudge.ui.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.astraedus.nudge.domain.widget.WidgetRefreshSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes fresh content to every Nudge widget.
 *
 * ## Why a push at all
 *
 * `updatePeriodMillis` is clamped to 30 minutes by the platform. That is a fine backstop for a
 * screen-time number, and useless for "I just walked away from Instagram" — which is the moment the
 * widget is worth having. So the widgets are pushed from the one chokepoint every block decision
 * and every walk-away already passes through (`UsageRepository.logEvent`), plus the master toggle.
 *
 * No WorkManager job: the existing 15-minute `ProtectionWatchdogWorker`, the 30-minute platform
 * tick and these event-driven pushes already cover every case, and a fourth scheduler would be a
 * fourth thing to keep alive.
 *
 * ## Why fire-and-forget, and why debounced
 *
 * `logEvent` runs on the accessibility hot path. [requestRefresh] is therefore NOT suspending and
 * does no work on the caller's thread beyond one atomic compare-and-set: it hands off to an
 * application-scoped coroutine and returns. A user hitting a wall of blocks can produce several
 * events a second, and each `updateAll` is a real RemoteViews build plus a binder call to the
 * launcher, so [WidgetRefreshDebouncer] coalesces them into at most one run per
 * [DEBOUNCE_MS]. Ten seconds is under the "did the number move?" threshold a person checking their
 * home screen would notice, and well above the burst rate.
 *
 * The whole body is wrapped in `runCatching`: a widget failing to update must never propagate an
 * exception back into an accessibility event dispatch, because that path is what does the blocking.
 */
@Singleton
class NudgeWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) : WidgetRefreshSignal {

    /**
     * Application-scoped on purpose: the refresh must outlive whatever short-lived scope logged the
     * event. `SupervisorJob` so one widget's failure cannot cancel its siblings' refresh.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val debouncer = WidgetRefreshDebouncer(DEBOUNCE_MS)

    override fun requestRefresh() {
        // elapsedRealtime, not currentTimeMillis: a wall-clock correction that jumped backwards
        // would otherwise lock refreshes out for however far it jumped, and a widget that silently
        // stops updating looks exactly like a widget with nothing to report.
        if (!debouncer.tryAcquire(SystemClock.elapsedRealtime())) return
        scope.launch {
            runCatching {
                TodayWidget().updateAll(context)
                TopBlockedWidget().updateAll(context)
                ProtectionWidget().updateAll(context)
            }.onFailure { error ->
                // The steady-state path is silent, but a FAILURE is logged: "the widgets are stale"
                // and "the widgets had nothing new to show" must never look the same in logcat.
                //
                // `android.util.Log` rather than the injected `NudgeLog`: `NudgeLogger` depends on
                // `NudgePreferences`, which now depends on this class through WidgetRefreshSignal,
                // so injecting the logger here would be a Dagger dependency cycle. Same direct-Log
                // precedent as `GrayscaleManager`.
                Log.w(TAG, "widget refresh failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "NudgeWidgetUpdater"

        /** Coalescing window. See the class KDoc for why ten seconds. */
        const val DEBOUNCE_MS = 10_000L
    }
}
