package com.astraedus.nudge.domain.model

enum class BlockMode {
    /**
     * Blocks nothing on its own. Exists so an app-level rule can carry the daily limit, the
     * interaction counter and the time-remaining overlay WITHOUT also gating the whole app — which
     * is what makes "block only Shorts, leave the rest of YouTube alone" expressible.
     *
     * Web-domain blocking IS carried, since issue #21: a rule's websites enforce at their own
     * [com.astraedus.nudge.data.db.entity.BlockRule.webBlockMode] (resolved by [WebBlockMode]),
     * so "the app opens normally, the website is blocked" is expressible. Before that fix, web
     * domains were evaluated through THIS mode and so enforced nothing at all on a NONE rule —
     * a blocker silently not blocking — and the config screen disabled the web toggle to hide it.
     *
     * NOT carried: grayscale. It is enforced only through a `BlockDecision.Block`, and a NONE
     * rule yields Allow — so a NONE rule's grayscale flag is inert, applying only while some
     * OTHER rule (e.g. a feature override) is blocking. Fixing that needs a separate enforcement
     * path in the service: there is no "grayscale while allowing" decision today, and
     * `BlockDecision.Block` is the only thing `GrayscaleManager` is driven from.
     *
     * Before this existed, [com.astraedus.nudge.ui.screens.config.UnifiedAppConfigScreen] always
     * wrote a blocking app-level rule and feature overrides could only differ from it, so a
     * feature-scoped rule with an unblocked host app could not be configured at all.
     *
     * [com.astraedus.nudge.domain.engine.BlockEngine] matches NONE in none of its block branches,
     * so it yields Allow. A daily limit on a NONE rule is still enforced — the time-budget check
     * keys off `dailyLimitMinutes`, not the mode — which is deliberate: "don't block it, but cap
     * it at 60 min/day" is a real thing users want.
     */
    NONE,
    HARD_BLOCK,
    DELAY,

    /**
     * Like [DELAY], except the clock only runs while a finger is on the screen.
     *
     * The user holds a target for the rule's `delaySeconds`; letting go puts the progress back to
     * zero and the whole wait starts over. Requested on
     * [issue #35](https://github.com/astraedus/nudge/issues/35): waiting is something a thumb can
     * pay for while the person is somewhere else entirely, so the entire price of a DELAY can be
     * spent without a decision ever being made. A hold is time the user has to keep CHOOSING, and
     * letting go is the cheap action -- the direction every affordance in this app should point.
     *
     * It reuses `delaySeconds` rather than adding a column: the number means the same thing (how
     * long before the app opens) and the editor offers the same picker, so a rule switched between
     * DELAY and HOLD keeps its duration. That is also why [com.astraedus.nudge.domain.lock.RuleWeakening]
     * ranks the two EQUAL: at one duration they cost the same wall-clock time, and a hold costs
     * strictly more attention, so flipping between them is not a way around Strict Mode.
     */
    HOLD,
    BREATHING;

    /**
     * Whether this mode spends the rule's `delaySeconds`, i.e. whether a duration picker is
     * meaningful for a rule carrying it.
     *
     * Asked positively of the enum rather than as `mode == DELAY || mode == BREATHING` at each of
     * the three call sites that used to spell it out: a mode added without being listed there would
     * have silently shipped with an uneditable duration, stuck on whatever was last saved.
     */
    val usesDuration: Boolean get() = this == DELAY || this == HOLD || this == BREATHING
}
