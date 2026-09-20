package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One block/allow *decision* made by the engine — not a foreground-time sample.
 *
 * Screen time comes from `UsageStatsManager` via `ScreenTimeProvider`; this table only ever
 * answers "what did we decide, for which app, when". A `durationMs` column lived here until
 * issue #22 but was never written, so every consumer that summed it read 0 forever.
 */
@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wasBlocked: Boolean = false,
    val blockMode: String? = null,
    val userChangedMind: Boolean = false
)

/**
 * **What the word "Blocked" means everywhere in this app: one confrontation the user was actually
 * shown.**
 *
 * One confrontation writes up to TWO rows. The overlay going up writes
 * `wasBlocked = true, userChangedMind = false`; if the user then taps "I changed my mind" the
 * walk-away writes a SECOND row for the SAME confrontation, `wasBlocked = true` **and**
 * `userChangedMind = true` (see `RecordWalkAwayUseCase.buildEvent`, where that shape is
 * deliberate and pinned by a test). An ALLOW decision writes `wasBlocked = false`.
 *
 * So `wasBlocked` alone is not "blocked" as a user reads it: it is "a row belonging to a
 * confrontation", and every walk-away contributes two of them. Counting it raw is why the home
 * dashboard's Blocked tile rose by 2 per walk-away and read 210 on a device that had faced 181
 * confrontations, while the Interventions screen — which classified its rows — read the truth.
 *
 * ## Why this predicate, rather than per-screen arithmetic
 *
 * The other available correction is `blocked - changedMind`, which the Interventions screen used.
 * It gives the same answer on well-formed data, but it is arithmetic each screen has to remember
 * to do, it needs TWO reads to answer ONE question, and it silently under-reports if a
 * `userChangedMind` row ever loses its paired show row (a trimmed import, a retention sweep that
 * cut a window mid-confrontation). Naming the predicate once instead means no screen can express
 * "blocked" any other way: in-memory consumers call this, and the count queries in
 * `UsageEventDao` are its SQL mirror (`wasBlocked = 1 AND userChangedMind = 0`), with the raw
 * "count every `wasBlocked` row" queries deleted so the old number is not reachable at all.
 * `BlockedCountSemanticsContractTest` pins both halves.
 *
 * Note that the EXPORT path deliberately keeps reading `wasBlocked` raw: a backup carries rows,
 * not tiles, and both rows of a confrontation must survive a round trip byte for byte.
 */
val UsageEvent.isShownConfrontation: Boolean
    get() = wasBlocked && !userChangedMind
