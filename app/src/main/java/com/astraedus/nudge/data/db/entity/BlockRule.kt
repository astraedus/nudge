package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String? = null,
    val groupId: Long? = null,
    val mode: String,
    val delaySeconds: Int = 15,
    val dailyLimitMinutes: Int? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // Schedule-based rules (Feature 1)
    val scheduleDays: String? = null,       // comma-separated: "1,2,3,4,5" (1=Mon..7=Sun). null = every day
    val scheduleStartMinute: Int? = null,   // minutes from midnight. 540 = 9:00 AM. null = no schedule
    val scheduleEndMinute: Int? = null,     // minutes from midnight. 1020 = 5:00 PM. null = no schedule
    // In-app feature blocking (Feature 2)
    val inAppFeatures: String? = null,      // comma-separated: "REELS,SHORTS". null = block whole app
    // Grayscale mode (Feature 3)
    val grayscale: Boolean = false,
    // Interaction counter overlay
    val showCounter: Boolean = false,
    // Auto-kick: send user to home screen after this many scrolls (null = disabled)
    val autoKickAfter: Int? = null,
    // Show remaining daily time as overlay
    val showTimeRemaining: Boolean = false,
    // Cooldown after auto-kick in seconds (0 = no cooldown)
    val autoKickCooldownSeconds: Int = 60,
    // Web domain blocking (comma-separated: "instagram.com,www.instagram.com")
    val webDomains: String? = null,
    /**
     * Block mode used for [webDomains], INDEPENDENT of the app-level [mode].
     *
     * NULL = inherit [mode] (the historical behaviour, and what every rule written before this
     * column existed carries). A non-null value wins, which is what makes "don't block the app,
     * do block the site" expressible: with [mode] = NONE the app opens freely while the website
     * still enforces. Resolution lives in
     * [com.astraedus.nudge.domain.model.WebBlockMode.resolve] — never read this field raw.
     */
    val webBlockMode: String? = null,
    // Time-based auto-kick: send user to home screen after this many minutes of foreground time in
    // one session (null = disabled). Independent of [autoKickAfter]; whichever fires first kicks.
    val autoKickAfterMinutes: Int? = null,
    /**
     * Usage reminder ([#77](https://github.com/astraedus/nudge/issues/77)): after this many minutes
     * of foreground time in one session, post ONE gentle notification ("You've been on Instagram
     * for 20 min") and change nothing else. Null = off. Measured on the SAME session clock as
     * [autoKickAfterMinutes] (one baseline, one reset), so the two can never disagree about which
     * sitting this is; when both are set and this is not below the kick, the kick wins and the
     * reminder never fires ([com.astraedus.nudge.domain.autokick.UseReminderEvaluator]).
     *
     * A separate field, never a "remind instead of kick" mode on [autoKickAfterMinutes]: that would
     * turn a Strict-locked kick into a notification. A reminder only ever adds, and like
     * [showCounter] it enforces nothing, so it is not a [com.astraedus.nudge.domain.lock.RuleWeakening]
     * axis in either direction.
     */
    val remindAfterMinutes: Int? = null,
    /**
     * While this rule DECIDES a HARD_BLOCK on Instagram REELS, draw a cover over the Reels nav
     * tab so it disappears instead of merely refusing to open ([BlockDecision.Block.tabVanish]
     * is read off the deciding rule, not `any {}` across every applicable rule). It can only ever
     * activate behind a decision that is already a hard block, so it adds enforcement only where
     * the user already chose the strongest mode -- hence **default true**. Turning it off is a
     * Strict-Mode weakening axis; see [com.astraedus.nudge.domain.lock.RuleWeakening].
     */
    val tabVanish: Boolean = true,
    /**
     * Experimental, per-rule opt-in: on arrival at Instagram's Home feed, steer it to the
     * Following feed. This is the first thing Nudge does *inside* another app rather than around
     * it, which is a materially different kind of risk than blocking -- hence **default false**,
     * unlike every other capability flag on this entity. It is not a weakening axis: it is a
     * steer, never a block, so it is deliberately excluded from
     * [com.astraedus.nudge.domain.lock.RuleWeakening].
     */
    val followingSteer: Boolean = false,
    /**
     * "Keep the pass on short trips" ([#110](https://github.com/astraedus/nudge/issues/110)),
     * per-rule opt-in, **default false**. With it on, once the user has waited out this rule's
     * delay (or hold, or breathing exercise), going to the home screen no longer revokes the pass
     * at once: it starts the same 2-minute away clock another app or a screen-off already starts,
     * so a gesture swipe through the launcher and straight back keeps the pass. Off is the
     * shipped behaviour: Home ends the sitting and the next open costs a fresh wait.
     *
     * Read off the rule that DECIDED the block ([com.astraedus.nudge.domain.model.BlockDecision.Block.keepPassOnShortTrips]), the
     * same way as [tabVanish], and handed to the grant, so it only ever affects a pass this rule
     * granted. Turning it ON is a Strict-Mode weakening axis; see
     * [com.astraedus.nudge.domain.lock.RuleWeakening].
     */
    val keepPassOnShortTrips: Boolean = false,
    /**
     * The rule's target is every app on the phone except the ones it lists (spec 5.1, "All apps
     * except a few"). Exactly one of [packageName], [groupId] and this is set; the repository
     * enforces that on write and code reads [com.astraedus.nudge.domain.model.RuleTarget], never
     * the raw columns. Default false, so every pre-migration rule keeps its target.
     */
    val allApps: Boolean = false,
    /**
     * An All apps rule's allowed apps: comma-separated package names, the codebase's CSV idiom
     * (like [webDomains]). Read through [com.astraedus.nudge.domain.model.ExceptCsv]. Null for
     * every other rule. An uninstalled package stays here harmlessly and is allowed again if it is
     * reinstalled.
     */
    val exceptPackages: String? = null,
    /**
     * An All apps rule's allowed GROUPS: comma-separated group ids, live references (membership is
     * resolved at evaluation, so an app that joins the group later is allowed from then on). A stale
     * id resolves to no members, which only makes the rule stricter. Deleting a group removes its id
     * from every rule in the same transaction.
     */
    val exceptGroupIds: String? = null,
    /**
     * Non-null = active for this many minutes after the first unlock of the logical day (spec 5.3).
     * Null = not a first-unlock rule, and every rule written before DB 15->16 carries null, so its
     * schedule keeps its old meaning exactly. Read through
     * [com.astraedus.nudge.domain.engine.FirstUnlockWindow]; check it before [scheduleStartMinute]
     * and [scheduleEndMinute], which are ignored when this is set.
     */
    val firstUnlockMinutes: Int? = null,
    /**
     * Wall-clock ms this rule was last saved or switched on (spec 5.1, arming). A first-unlock
     * window only counts if its stamp is at or after this, so a rule made at 08:05 after an 08:00
     * unlock starts tomorrow instead of locking the phone mid-setup (owner decision 2).
     *
     * 0 for every pre-migration rule, which means "always armed": those rules did not have this
     * behaviour to wait for. Stamped by [com.astraedus.nudge.data.repository.BlockRuleRepository] on
     * every enabled write, so callers cannot forget it.
     */
    val armedAt: Long = 0L
)
