package com.astraedus.nudge.service

import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.Countability
import com.astraedus.nudge.domain.events.AccessibilityEventCodec
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.util.NudgeLogger

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
 * **Cost.** Writes through [NudgeLogger], which already owns the debug gate — a debug build, or
 * the seven-tap debug-logging preference. It used to carry its own `enabled` lambda plus a raw
 * `Log.i`, which is two places that decide whether debug output happens and one of them able to
 * drift: a future change to how the preference is read would have silenced every other log in the
 * app while this one kept writing. In a release build with the preference off, [record] does one
 * boolean test and returns, so the release hot path is unchanged.
 *
 * The binder read for `viewIdResourceName` is a separate decision (see
 * [AccessibilityEventRecordFactory] and `InteractionHandler.sourceViewIdFor`); this class only
 * reports what it cost, via [noteSourceReadCost], as a periodic `cost` line so a capture run
 * answers "can we afford this?" with numbers instead of opinion.
 */
class AccessibilityEventTrace(
    private val logger: NudgeLogger,
    /** Test seam. Production writes through [logger] under its own tag. */
    private val emit: (String) -> Unit = { logger.i(message = it, tag = TAG) }
) {
    private var sourceReads = 0L
    private var sourceReadNanos = 0L

    fun isEnabled(): Boolean = logger.isDebugEnabled

    fun record(record: AccessibilityEventRecord) {
        if (!isEnabled()) return
        emit(PREFIX + AccessibilityEventCodec.encode(record))
    }

    /**
     * Accumulate the cost of one `viewIdResourceName` binder read and emit a summary every
     * [COST_REPORT_INTERVAL] reads. Reported as total + mean so a capture shows both the per-event
     * cost and what it adds up to over a real scrolling session.
     */
    fun noteSourceReadCost(nanos: Long) {
        if (!isEnabled()) return
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

    /**
     * The bounce check-in fired (#181): what it counted, so a capture shows WHICH packages made the
     * number. An APPS alert lists its packages; a SITES alert lists none, and no domain is ever
     * written (domains are never logged).
     */
    fun bounce(alert: BounceAlert) {
        if (!isEnabled()) return
        val unit = if (alert.unit == BounceAlert.Unit.SITES) "sites" else "apps"
        val apps = alert.packages.joinToString(",", "[", "]") { jsonString(it) }
        emit(
            BOUNCE_PREFIX + "{\"unit\":\"$unit\",\"count\":${alert.count}," +
                "\"minutes\":${alert.minutes},\"walls\":${alert.walls},\"apps\":$apps}"
        )
    }

    /** The bounce detector left [packageName] out of the count, and why (#181). Once per streak. */
    fun bounceSkip(packageName: String, why: Countability) {
        if (!isEnabled()) return
        val reason = when (why) {
            Countability.NOT_AN_APP -> "not_launchable"
            Countability.HOME -> "home"
            Countability.UNKNOWN -> "unknown"
            Countability.COUNTS -> "counts"
        }
        emit(BOUNCE_SKIP_PREFIX + "{\"pkg\":${jsonString(packageName)},\"why\":\"$reason\"}")
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Dedicated tag so a capture is one `logcat -s` away, never a grep through service noise. */
        const val TAG = "NudgeA11yTrace"

        /** Line marker. `scripts/a11y-capture.sh` keeps everything after it, verbatim. */
        const val PREFIX = "EV "

        /** Marker for the periodic source-read cost summary. Not part of a capture's event stream. */
        const val COST_PREFIX = "COST "

        /** Marker for a fired bounce check-in. Not part of a capture's event stream. */
        const val BOUNCE_PREFIX = "BOUNCE "

        /** Marker for a package the bounce count left out. Not part of a capture's event stream. */
        const val BOUNCE_SKIP_PREFIX = "BOUNCE-SKIP "

        /**
         * How many reads between cost summaries. Low enough that a 30-second capture of ordinary
         * scrolling produces at least one line — a cost report nobody ever sees is not a measurement.
         */
        private const val COST_REPORT_INTERVAL = 25L
    }
}
