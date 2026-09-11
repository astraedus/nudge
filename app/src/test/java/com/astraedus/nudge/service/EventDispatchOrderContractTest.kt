package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on the ORDER of `NudgeAccessibilityService.onAccessibilityEvent`.
 *
 * ## Why a test that reads source code
 *
 * Every defect this subsystem has shipped was an ordering bug, not a logic bug:
 *
 * | Issue | What actually went wrong |
 * |---|---|
 * | [#5](https://github.com/astraedus/nudge/issues/5) | a keyboard's window event reached the passthrough clear |
 * | Home path (v1.13) | the `SYSTEM_PACKAGES` return fired ~200 lines before the clear |
 * | [#7](https://github.com/astraedus/nudge/issues/7) | a content-change re-entry returned before evaluation |
 * | [#19](https://github.com/astraedus/nudge/issues/19) | the PiP gate was attached to one branch instead of the pipeline |
 * | [#28](https://github.com/astraedus/nudge/issues/28) | any foreign package's event reached the passthrough clear |
 *
 * Each fix was correct and each left the same shape in place: "what is on screen" derived
 * independently inside several branches, each free to return before something that had to happen. No
 * value-level test can see where a `return` sits, which is why `HomeScreenPassthroughContractTest`
 * and `BlockOverlayWalkAwayContractTest` already work this way.
 *
 * The structural fix is that the event is classified ONCE and the sitting updated ONCE, both above
 * every `return` in the method. This test is what keeps that true: it fails the moment someone adds
 * an early return above the classification, or re-derives the classification below it.
 */
class EventDispatchOrderContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"),
            File("app/src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt")
        )
        (candidates.firstOrNull { it.exists() }
            ?: error("NudgeAccessibilityService.kt not found from ${File("").absolutePath}"))
            .readText()
    }

    /** The body of `onAccessibilityEvent`, up to the start of the next top-level declaration. */
    private val dispatchBody: String by lazy {
        val start = source.indexOf("override fun onAccessibilityEvent(")
        assertTrue("onAccessibilityEvent must exist", start >= 0)
        val end = source.indexOf("\n    /**\n     * Feed the classified signal", start)
        assertTrue("the dispatch body must be followed by applySitting's doc", end > start)
        source.substring(start, end)
    }

    /**
     * The same body with comments removed.
     *
     * Load-bearing, not tidiness. This file's comments necessarily QUOTE the code they are about —
     * the block explaining the fix says the word "return" several times, and the one marking issue
     * #28's old line names `clearIfAppChanged(` verbatim. A contract test that greps raw source
     * therefore reads its own explanations as code and fails, or worse, passes because a comment
     * mentions the thing the code no longer does. Both happened while writing this test.
     */
    private val dispatchCode: String by lazy { stripComments(dispatchBody) }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private fun indexIn(body: String, needle: String): Int {
        val i = body.indexOf(needle)
        assertTrue("expected to find `$needle` in onAccessibilityEvent", i >= 0)
        return i
    }

    /**
     * The whole point. Classification and the sitting update must precede EVERY early return except
     * the three that cannot proceed without them: a null event, a null package, and a record the
     * factory could not build — none of which carry any information to act on.
     */
    @Test
    fun `the event is classified and the sitting updated before any early return`() {
        val classify = indexIn(dispatchCode, "eventClassifier.classify(")
        val applySitting = indexIn(dispatchCode, "applySitting(signal)")
        assertTrue("the sitting must be updated from the classification", applySitting > classify)

        val preamble = dispatchCode.substring(0, dispatchCode.indexOf("applySitting(signal)"))
        // The only returns allowed above the sitting update are the "there is nothing to classify"
        // ones. Anything else is a branch that can skip the sitting, which is this whole bug family.
        val allowedPreambleReturns = listOf(
            "if (event == null) return",
            "val packageName = event.packageName?.toString() ?: return",
            "val record = eventRecordFactory.toRecord(event) ?: return",
            "if (PipEscapeActivity.isActive) return"
        )
        val returns = Regex("""\breturn\b""").findAll(preamble).count()
        assertTrue(
            "every return above applySitting must be one of the allowed 'nothing to classify' " +
                "returns; found $returns returns and ${allowedPreambleReturns.size} allowed forms",
            returns <= allowedPreambleReturns.size
        )
        allowedPreambleReturns.forEach { form ->
            assertTrue("expected the allowed early return `$form` to still be present", preamble.contains(form))
        }
    }

    /**
     * One classification per event. A second `classify(` call in the dispatch would be a second
     * opinion about what is on screen, which is exactly the condition the six hand-rolled package
     * tests used to create.
     */
    @Test
    fun `the dispatch classifies exactly once`() {
        assertTrue(
            "onAccessibilityEvent must call the classifier exactly once",
            Regex("""eventClassifier\.classify\(""").findAll(dispatchCode).count() == 1
        )
        assertTrue(
            "onAccessibilityEvent must apply the sitting exactly once",
            Regex("""applySitting\(""").findAll(dispatchCode).count() == 1
        )
    }

    /**
     * The branches below must READ the signal, never re-derive it. A re-derivation is how the Pixel's
     * `com.google.android.permissioncontroller` ended up classified two different ways in one file.
     */
    @Test
    fun `the dispatch branches on the signal rather than on package sets`() {
        listOf(
            "packageName in SYSTEM_PACKAGES",
            "isTransientNonAppPackage(packageName",
            "packageName == applicationContext.packageName",
            "packageName in pipOnlyPackagesCached"
        ).forEach { rederivation ->
            assertFalse(
                "onAccessibilityEvent must not re-derive `$rederivation` — it has the signal",
                dispatchCode.contains(rederivation)
            )
        }
        listOf(
            "ForegroundSignal.PipOnly",
            "ForegroundSignal.OwnUi",
            "ForegroundSignal.Transient",
            "ForegroundSignal.Home",
            "ForegroundSignal.SystemSurface"
        ).forEach { branch ->
            assertTrue("the dispatch must branch on $branch", dispatchCode.contains(branch))
        }
    }

    /**
     * Issue #28's actual line. `clearIfAppChanged` on the app-switch path is what revoked a grant
     * for a photo picker, a share sheet or a permission dialog. It must never come back to
     * `evaluateForegroundPackage`: revocation belongs to the sitting, which a sub-flow cannot end.
     */
    @Test
    fun `evaluateForegroundPackage never clears the passthrough on an app switch`() {
        val start = source.indexOf("private fun evaluateForegroundPackage(")
        assertTrue("evaluateForegroundPackage must exist", start >= 0)
        val end = source.indexOf("\n    private suspend fun evaluateWebDomain(", start)
        assertTrue("evaluateForegroundPackage must be followed by evaluateWebDomain", end > start)
        // Comments stripped: the function deliberately carries a marker comment naming the removed
        // call, so that the next person to read it knows what used to be there and why it is gone.
        val body = stripComments(source.substring(start, end))
        assertFalse(
            "the app-switch passthrough clear is issue #28 — the sitting owns revocation now",
            body.contains("clearIfAppChanged(")
        )
        assertFalse(
            "the same defect one axis over: leaving a browser for a picker or a share sheet must " +
                "not revoke a completed WEB delay either. The sitting owns both axes; a genuine " +
                "navigation is caught by WebDomainGate.EVALUATE, which actually knows the domain " +
                "changed",
            body.contains("clearWebGrant()")
        )
        assertTrue(
            "leaving the browser must still stop the web foreground-time CLOCK, which is not a grant",
            body.contains("endWebSession(\"left_the_browser\")")
        )
    }

    /**
     * THE SECOND ENTRY POINT, and the one that silently bypassed the sitting.
     *
     * `maybeEvaluateContentChangeAsAppSwitch` is issue #7's fallback: a re-entry via a notification
     * tap or the recents overview sometimes arrives as a content change only, and once verified
     * against the real active window it is routed straight into `evaluateForegroundPackage`. The
     * classification at the top of `onAccessibilityEvent` correctly saw a `NotForeground` signal for
     * that event and did nothing with it, so for the WHOLE of that path the sitting never moved.
     *
     * Concretely: switch away from a granted app, come back via a notification half an hour later,
     * and the away clock never started, so the delay is skipped. A bypass introduced by the fix for
     * a bypass, invisible to every value-level test because both functions are individually correct.
     *
     * Hence this test, and hence the rule it encodes: EVERY path into `evaluateForegroundPackage`
     * must have updated the sitting first.
     */
    @Test
    fun `the content-change app-switch fallback updates the sitting before evaluating`() {
        val start = source.indexOf("private fun maybeEvaluateContentChangeAsAppSwitch(")
        assertTrue("the issue #7 fallback must still exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        val body = stripComments(source.substring(start, if (end > start) end else source.length))

        val applySitting = body.indexOf("applySitting(")
        val evaluate = body.indexOf("evaluateForegroundPackage(")
        assertTrue("the fallback must apply the sitting", applySitting >= 0)
        assertTrue("the fallback must evaluate", evaluate >= 0)
        assertTrue(
            "the sitting must be updated BEFORE evaluation, or a notification re-entry never " +
                "starts the away clock and the delay is skipped forever",
            applySitting < evaluate
        )
        assertTrue(
            "a verified content change must be promoted to a real foreground signal, not " +
                "hand-built, so the promotion rules live in one place",
            body.contains("classifyVerifiedContentChangeAsSwitch(")
        )
    }

    /**
     * The ORDER inside that fallback, which is what let the parallel eligibility gate be deleted.
     *
     * `shouldTreatContentChangeAsAppSwitch` used to hand-test blank / own / system / transient
     * before `EventClassifier` tested the same things again -- two answers to "what is on screen" on
     * the one entry point where they could disagree. Collapsing them is only safe while the
     * classification stays CHEAP and comes first, and the binder read stays LAST: this path runs on
     * every content-change event of every app, so a node read reached before the free checks would
     * put an IPC on the hot path for the overwhelmingly common case.
     *
     * `ContentChangeAppSwitchTest` asserts the same order at the value level, but it models the
     * sequence locally; this is what stops the model and the service drifting apart.
     */
    @Test
    fun `the content-change fallback classifies before it reads the active window`() {
        val start = source.indexOf("private fun maybeEvaluateContentChangeAsAppSwitch(")
        assertTrue("the issue #7 fallback must still exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        val body = stripComments(source.substring(start, if (end > start) end else source.length))

        val cheapReject = body.indexOf("packageName == lastPackage")
        val classify = body.indexOf("classifyVerifiedContentChangeAsSwitch(")
        val activeWindow = body.indexOf("activeWindowPackageOrNull()")
        assertTrue("the cheap same-package rejection must come first", cheapReject >= 0)
        assertTrue("the active window must still be verified", activeWindow >= 0)
        assertTrue("the cheap rejection must precede classification", cheapReject < classify)
        assertTrue(
            "the binder read must come LAST, after every free check has had its say",
            classify < activeWindow
        )
        assertFalse(
            "the parallel eligibility gate must not come back -- one classifier, one answer",
            // Comments stripped: the fallback deliberately NAMES the deleted gate, so that whoever
            // reads it next knows what used to be there. Grepping raw source would read that
            // explanation as the thing it explains. Third time this file has had to say so.
            stripComments(source).contains("shouldTreatContentChangeAsAppSwitch")
        )
    }

    /**
     * INVERTED, and the inversion is the point.
     *
     * This used to require `onServiceConnected` to call `resetSitting()`, on the reasoning that a
     * bind is the start of observation so nothing before it can be trusted. That is right about the
     * away CLOCK and wrong about the grant, and device QA on 2026-09-12 showed what the difference
     * costs: `docs/BACKLOG.md` records this service churning and reconnecting under memory pressure,
     * so revoking on every bind re-blocks a user who never left their app -- issue #28's own defect,
     * reintroduced by its own fix.
     *
     * `onObservationResumed()` discards the clock and keeps the sitting. The failure direction is
     * the one this subsystem has always chosen: miss a revoke rather than interrupt someone mid-use.
     */
    @Test
    fun `reconnecting discards the away clock but does not revoke the grant`() {
        val start = source.indexOf("override fun onServiceConnected()")
        assertTrue("onServiceConnected must exist", start >= 0)
        val end = source.indexOf("\n    override fun ", start + 1)
        val body = stripComments(source.substring(start, if (end > start) end else source.length))
        assertTrue(
            "a fresh bind must discard the away clock, which was timing an interval whose end we " +
                "did not see",
            body.contains("onObservationResumed()")
        )
        assertFalse(
            "a rebind must NOT revoke the grant -- this service reconnects often enough that doing " +
                "so re-blocks users mid-session",
            body.contains("resetSitting()")
        )
    }

    /**
     * Detection must be REPORTED before it is acted on, null included.
     *
     * `detectAndEvaluateFeature` did `detectFeature(...) ?: return`, so `noteDetectedFeature` was
     * only ever reached with a recognised feature. The handler's null branch -- whose entire job is
     * to notice that the caption no longer describes what is on screen -- was therefore unreachable
     * from production, and the caption stayed stuck to whatever surface last set it (issue #28
     * FAIL 2, device QA 2026-09-12).
     *
     * This is a source-level pin because it is an ORDERING fact: the handler's own tests pass with
     * or without it, since they call `noteDetectedFeature(pkg, null)` directly. Only the caller can
     * be wrong, and only here is that visible.
     */
    @Test
    fun `the feature detector reports its result before the null early-return`() {
        val start = source.indexOf("private fun detectAndEvaluateFeature(")
        assertTrue("detectAndEvaluateFeature must exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        val body = stripComments(source.substring(start, if (end > start) end else source.length))

        assertFalse(
            "detectFeature must not early-return on null before the handler is told -- that is what " +
                "made the caption invalidation unreachable",
            body.contains("detectFeature(packageName, rootNode) ?: return")
        )
        val note = body.indexOf("noteDetectedFeature(")
        val nullReturn = body.indexOf("if (feature == null) return")
        assertTrue("the handler must be told the detection result", note >= 0)
        assertTrue("a null result must still stop the BLOCK evaluation", nullReturn >= 0)
        assertTrue("but only AFTER the handler has been told", note < nullReturn)
    }

    /**
     * The trace has to see the events that get DROPPED — a picture-in-picture bubble, our own
     * window, a keyboard, a system surface — because those are what a report in this family turns
     * out to be about. A trace that only saw the events we already act on would record our
     * assumptions rather than the device.
     */
    @Test
    fun `the event trace runs before every gate`() {
        val trace = indexIn(dispatchCode, "eventTrace.record(record)")
        val pipGate = indexIn(dispatchCode, "PipEscapeActivity.isActive")
        val classify = indexIn(dispatchCode, "eventClassifier.classify(")
        assertTrue("the trace must precede the PiP-explainer gate", trace < pipGate)
        assertTrue("the trace must precede classification", trace < classify)
    }

    /**
     * The trace must stay free in a release build with debug logging off. `AccessibilityEventTrace`
     * gates internally, and the one genuinely expensive field — the `viewIdResourceName` binder read
     * — must be paid only while the trace is on.
     */
    @Test
    fun `the source-node binder read is paid only while tracing`() {
        val start = source.indexOf("private val eventRecordFactory by lazy {")
        assertTrue("eventRecordFactory must exist", start >= 0)
        val end = source.indexOf("\n    }", start)
        val body = stripComments(source.substring(start, end))
        assertTrue(
            "the factory must only put the source id in the RECORD while tracing",
            body.contains("eventTrace.isEnabled()") &&
                body.contains("SourceReadPolicy.NEVER")
        )

        // ...and the OTHER caller must keep reading it in every build. This half is what makes the
        // counter able to tell a comments sheet from the feed behind it, so an "optimisation" that
        // gated it on the trace would silently break the fix in release only -- the worst place.
        val handler = listOf(
            java.io.File("src/main/java/com/astraedus/nudge/service/InteractionHandler.kt"),
            java.io.File("app/src/main/java/com/astraedus/nudge/service/InteractionHandler.kt")
        ).first { it.exists() }.readText()
        assertFalse(
            "the counter's source read must NOT be gated on debug logging",
            stripComments(handler).contains("isDebugEnabled")
        )
    }
}
