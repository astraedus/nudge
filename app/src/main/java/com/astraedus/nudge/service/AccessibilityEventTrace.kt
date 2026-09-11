package com.astraedus.nudge.service

import android.util.Log
import com.astraedus.nudge.domain.events.AccessibilityEventCodec
import com.astraedus.nudge.domain.events.AccessibilityEventRecord

/**
 * Writes one machine-readable line per accessibility event to logcat, so a real device session can
 * be captured and replayed as a test fixture (`scripts/a11y-capture.sh` →
 * `app/src/test/resources/a11y-captures/<name>.jsonl`).
 *
 * **Why this exists.** Every bug this subsystem has shipped — #5, #7, #19, #28 — was a disagreement
 * between what we assumed the event stream looked like and what it actually looked like, and each
 * one cost a device cycle (or several) to localise by guessing. A capture turns that into evidence:
 * the stream is written down once, the fix is written against it, and the same stream becomes the
 * regression test. See `docs/architecture/accessibility-event-pipeline.md`.
 *
 * **Cost.** Gated on [enabled], which the service wires to `NudgeLogger.isDebugEnabled` — a debug
 * build, or the seven-tap debug-logging preference. In a release build with that preference off,
 * [record] does one boolean test and returns, so the release hot path is unchanged. The binder read
 * for `viewIdResourceName` is a separate decision made by [AccessibilityEventRecordFactory]; this
 * class only reports what it cost, via [noteSourceReadCost], as a periodic `cost` line so a capture
 * run answers "can we afford this in production?" with numbers instead of opinion.
 */
class AccessibilityEventTrace(
    private val enabled: () -> Boolean,
    private val emit: (String) -> Unit = { Log.i(TAG, it) }
) {
    private var sourceReads = 0L
    private var sourceReadNanos = 0L

    fun isEnabled(): Boolean = enabled()

    fun record(record: AccessibilityEventRecord) {
        if (!enabled()) return
        emit(PREFIX + AccessibilityEventCodec.encode(record))
    }

    /**
     * Accumulate the cost of one `viewIdResourceName` binder read and emit a summary every
     * [COST_REPORT_INTERVAL] reads. Reported as total + mean so a capture shows both the per-event
     * cost and what it adds up to over a real scrolling session.
     */
    fun noteSourceReadCost(nanos: Long) {
        if (!enabled()) return
        sourceReads++
        sourceReadNanos += nanos
        if (sourceReads % COST_REPORT_INTERVAL != 0L) return
        val meanMicros = (sourceReadNanos / sourceReads) / 1_000.0
        val totalMillis = sourceReadNanos / 1_000_000.0
        emit(
            COST_PREFIX + "{\"reads\":$sourceReads," +
                "\"meanMicros\":${"%.1f".format(meanMicros)}," +
                "\"totalMillis\":${"%.1f".format(totalMillis)}}"
        )
    }

    companion object {
        /** Dedicated tag so a capture is one `logcat -s` away, never a grep through service noise. */
        const val TAG = "NudgeA11yTrace"

        /** Line marker. `scripts/a11y-capture.sh` keeps everything after it, verbatim. */
        const val PREFIX = "EV "

        /** Marker for the periodic source-read cost summary. Not part of a capture's event stream. */
        const val COST_PREFIX = "COST "

        /**
         * How many reads between cost summaries. Low enough that a 30-second capture of ordinary
         * scrolling produces at least one line — a cost report nobody ever sees is not a measurement.
         */
        private const val COST_REPORT_INTERVAL = 25L
    }
}
