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
    val followingSteer: Boolean = false
)
