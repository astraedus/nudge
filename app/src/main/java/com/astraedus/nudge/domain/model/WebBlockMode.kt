package com.astraedus.nudge.domain.model

/**
 * Resolves the block mode a rule's WEB DOMAINS enforce with (issue #21).
 *
 * Web enforcement used to run through the app-level mode verbatim, so a rule with
 * [BlockMode.NONE] — "don't gate the app itself", the state that makes Shorts-only blocking
 * expressible — silently allowed every configured website too. A blocker that quietly enforces
 * nothing is the worst failure this app can have, so the two decisions are now separable:
 *
 *  - `webBlockMode == null` -> inherit the app-level mode. Every rule written before the column
 *    existed carries null, so their behaviour is unchanged.
 *  - `webBlockMode != null` -> that mode wins, whatever the app-level mode is. This is how
 *    "the app opens normally, the website is hard-blocked" is expressed.
 *
 * A resolved [BlockMode.NONE] means this rule enforces nothing on the web (the app-level mode is
 * NONE and no independent web mode was chosen). Callers must treat that as "not an enforcing
 * rule", not as a block with no mode.
 *
 * Pure Kotlin — no Android imports, no Room.
 */
object WebBlockMode {

    /**
     * @param ruleMode the rule's app-level `mode` column.
     * @param webBlockMode the rule's `webBlockMode` column (null = inherit [ruleMode]).
     */
    fun resolve(ruleMode: String?, webBlockMode: String?): BlockMode =
        parse(webBlockMode) ?: parse(ruleMode) ?: BlockMode.HARD_BLOCK

    /**
     * Null for a null/unrecognized value. Unrecognized is deliberately NOT collapsed into a
     * default here: [resolve] must fall back from an unparseable web mode to the app-level mode,
     * and only fail toward HARD_BLOCK (enforcement) when neither is readable — the same direction
     * `EvaluateBlockUseCase` already takes for a corrupt mode string.
     */
    private fun parse(mode: String?): BlockMode? = when (mode) {
        "NONE" -> BlockMode.NONE
        "HARD_BLOCK" -> BlockMode.HARD_BLOCK
        "DELAY" -> BlockMode.DELAY
        "HOLD" -> BlockMode.HOLD
        "BREATHING" -> BlockMode.BREATHING
        else -> null
    }
}
