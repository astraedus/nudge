package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.util.CrashSafeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rule tables, held in memory and rebuilt only when one of them changes (spec 5.7).
 *
 * Before this, every block evaluation opened Room queries for all enabled rules, all groups and then
 * each group's members, one `Flow.first()` each, on the accessibility hot path. Here the three tables
 * are watched once ([BlockRuleRepository.getEnabledRules], [BlockRuleRepository.getAllGroups],
 * [BlockRuleRepository.getAllGroupMembers]) and combined into one [RuleSnapshot]. Room re-runs a
 * query only when its table is invalidated by a write, so once warm an evaluation reads no database
 * at all: [current] is a field read.
 *
 * ## Cold start: never "no rules"
 *
 * Until the first combined emission lands, [snapshot] is `null`, and [current] then reads the tables
 * directly through [RepositoryRuleSnapshots], which is the exact read every evaluation did before
 * this class existed. The alternatives were rejected on purpose:
 *
 * - **Evaluate an empty snapshot** (a `StateFlow` seeded with "no rules") would ALLOW every app for
 *   the first few hundred milliseconds after the process starts, which is precisely when a blocked
 *   app is being opened from a notification or a restored task. That is the fail-open this must not
 *   have.
 * - **Suspend until the first emission** couples every evaluation to the collector being alive, and
 *   needs a timeout that must then fail one way or the other. The direct read needs neither: it is
 *   correct on its own, and it is only ever paid until the first emission.
 *
 * A database that throws is handled the same way it was before: the cold read propagates the error
 * to the caller, and the collector retries with a backoff. A collector that fails AFTER warming keeps
 * serving the last snapshot it built (logged) rather than nothing.
 *
 * ## Freshness
 *
 * A rule edit reaches [snapshot] when Room's invalidation tracker re-runs the query, a few
 * milliseconds after the write commits. Nothing writes a rule and evaluates in the same breath (rules
 * are edited in Nudge's own screens, evaluation happens when another app comes to the front), so the
 * edit is in place by the next app open, with no restart.
 */
@Singleton
class RuleSnapshotSource internal constructor(
    repository: BlockRuleRepository,
    scope: CoroutineScope,
    logger: NudgeLog,
    private val retryBackoffMs: (attempt: Long) -> Long = ::defaultBackoffMs
) : RuleSnapshots {

    @Inject
    constructor(repository: BlockRuleRepository, logger: NudgeLog) : this(
        repository = repository,
        scope = CrashSafeScope.create(
            name = "rule-snapshot",
            dispatcher = Dispatchers.Default,
            logger = { logger.tagged(LOG_TAG) }
        ),
        logger = logger
    )

    private val log: NudgeLog = logger.tagged(LOG_TAG)
    private val direct = RepositoryRuleSnapshots(repository)

    /**
     * The latest snapshot, or `null` until the tables have been read once. Exposed for the service's
     * synchronous hot-path checks in later PRs (spec 5.5); [current] is what evaluation calls.
     */
    val snapshot: StateFlow<RuleSnapshot?> = combine(
        repository.getEnabledRules(),
        repository.getAllGroups(),
        repository.getAllGroupMembers()
    ) { rules, groups, members -> RuleSnapshot.of(rules, groups, members) }
        .retryWhen { cause, attempt ->
            log.w("rule snapshot read failed (attempt ${attempt + 1}); retrying", cause)
            delay(retryBackoffMs(attempt))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun current(): RuleSnapshot = snapshot.value ?: direct.current()

    override suspend fun enabledRules(): List<BlockRule> =
        snapshot.value?.enabledRules ?: direct.enabledRules()

    private companion object {
        const val LOG_TAG = "RuleSnapshot"

        fun defaultBackoffMs(attempt: Long): Long =
            (1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L)
    }
}
