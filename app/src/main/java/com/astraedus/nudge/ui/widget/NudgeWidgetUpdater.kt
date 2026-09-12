package com.astraedus.nudge.ui.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.UsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every Nudge widget in step with the data it displays.
 *
 * ## Why this OBSERVES rather than being told
 *
 * The first version exposed a `requestRefresh()` that every preference writer was supposed to call.
 * That is a rule, and rules get forgotten: three of the four writers never called it, so the
 * Protection widget - which is push-only (`updatePeriodMillis="0"`) and therefore has no tick to
 * fall back on - could never learn that protection had died, the single state it exists to
 * announce. A source-level test was then written to police the rule, which is a regex parser
 * simulating something the compiler should be enforcing.
 *
 * So the rule is gone. This class collects the **sources of truth** directly: the preference flows
 * the widgets read, and a change signal over `usage_events`. Any write through any instance, from
 * any layer, by any future caller, propagates - because it is the same DataStore and the same Room
 * table. "The widgets are fresh" is now a property of the wiring rather than of everyone's
 * discipline, and `NudgePreferences` and `UsageRepository` went back to knowing nothing about
 * widgets at all.
 *
 * The one thing still worth pinning is that the observed set MATCHES what the widgets read, which
 * is what `WidgetObservationContractTest` asserts - against the flows consumed, not against writers.
 *
 * ## Lifetime
 *
 * A `@Singleton` with an application-scoped `CoroutineScope`, started from [NudgeApp.onCreate] via
 * [start]. It must be constructed at process start rather than lazily on first widget read: a
 * widget's process is short-lived, so nothing else would hold these collectors open.
 */
@Singleton
class NudgeWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: NudgePreferences,
    private val usageRepository: UsageRepository
) {

    /**
     * Application-scoped: refreshes must outlive whatever short-lived scope caused the change.
     * `SupervisorJob` so one collector dying cannot take its siblings with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val coalescer = WidgetRefreshCoalescer(COOLDOWN_MS) { pushAll() }

    /** Idempotent: a second call is a no-op rather than a second set of collectors. */
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true

        // The loop first, so a request emitted by a source's initial value has somewhere to land.
        scope.launch { coalescer.run() }

        // The Protection widget's three inputs. distinctUntilChanged on the TRIPLE, not per-flow:
        // DataStore re-emits the whole preferences object on any write, so without this every
        // unrelated setting change would refresh the widgets.
        scope.launch {
            combine(
                preferences.isGlobalEnabled,
                preferences.protectionDegraded,
                preferences.isStrictModeEnabled
            ) { enabled, degraded, strict -> Triple(enabled, degraded, strict) }
                .distinctUntilChanged()
                .collect { coalescer.request() }
        }

        // Today + Top-blocked. MAX(id) over the primary key is the cheapest question that re-emits
        // on a write to usage_events; the widgets re-read their real data themselves.
        scope.launch {
            usageRepository.observeLatestEventId()
                .distinctUntilChanged()
                .collect { coalescer.request() }
        }
    }

    /**
     * Refreshes all three widgets CONCURRENTLY, each isolated from the others.
     *
     * Each widget gets its own child coroutine and its own `runCatching`. The previous version ran
     * all three sequentially inside ONE `runCatching`, so a throwing first widget starved the other
     * two - and its KDoc claimed the opposite, citing `SupervisorJob`, which isolates across
     * coroutines and does nothing for three calls in a row inside one of them.
     */
    private suspend fun pushAll() = coroutineScope {
        WIDGETS.forEach { (name, widget) ->
            launch {
                runCatching { widget().updateAll(context) }.onFailure { error ->
                    // The steady-state path is silent; a FAILURE is logged, because "the widgets
                    // are stale" and "the widgets had nothing new to show" must never look the
                    // same in logcat. The widget is NAMED so the failure is attributable.
                    //
                    // android.util.Log rather than the injected NudgeLog: NudgeLogger depends on
                    // NudgePreferences, which this class already depends on, so injecting it here
                    // would be a Dagger cycle. Same direct-Log precedent as GrayscaleManager.
                    Log.w(TAG, "widget refresh failed: $name", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "NudgeWidgetUpdater"

        /**
         * Floor between refreshes. Ten seconds is under the "did the number move?" threshold a
         * person glancing at a home screen would notice, and well above the burst rate.
         */
        const val COOLDOWN_MS = 10_000L

        /** Constructed per refresh: a `GlanceAppWidget` is cheap and holds no state worth reusing. */
        val WIDGETS: List<Pair<String, () -> GlanceAppWidget>> = listOf(
            "today" to { TodayWidget() },
            "topBlocked" to { TopBlockedWidget() },
            "protection" to { ProtectionWidget() }
        )
    }
}
