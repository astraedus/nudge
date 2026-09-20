package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a "I changed my mind" walk-away — the user was shown a block overlay and chose to leave
 * instead of waiting it out. This is the single write path behind the home screen's "Walked Away"
 * tile and the whole Willpower insight page, so it has exactly one job: **the row must land, and if
 * it ever does not, that must be visible in logcat.**
 *
 * It exists as a `@Singleton` use case rather than a `CoroutineScope(Dispatchers.IO).launch { … }`
 * inside [com.astraedus.nudge.ui.overlay.BlockOverlayActivity] because that shape had three defects,
 * every one of them silent:
 *
 *  - **The scope was unstructured and unowned.** A bare `CoroutineScope(…)` created per tap has no
 *    parent and nothing keeps a reference to it, so the write was a promise nobody held. Hoisting it
 *    into a process-lifetime singleton makes the write outlive the activity by construction — and
 *    the activity calls [record] and then immediately `finish()`es, so it always does.
 *  - **An insert failure crashed the process.** `launch` with no [CoroutineExceptionHandler]
 *    propagates to the thread's default handler; a Room/SQLite failure on this path would have taken
 *    the app (and with it the accessibility service) down rather than losing one stat row. The
 *    handler here demotes that to a logged error: losing a walk-away must never stop blocking.
 *  - **Nothing was logged either way.** "The row never appeared" and "the row appeared and the UI
 *    did not update" were indistinguishable from a device — the exact trap that cost a release cycle
 *    on the picture-in-picture work (see CLAUDE.md, "Logging is bounded and unconditional").
 *
 * [buildEvent] is pure and `internal` so the SHAPE of the row is pinned by a JVM test rather than
 * eyeballed on a device. That shape is load-bearing beyond this file: a walk-away row also carries
 * `wasBlocked = true`, so it would be counted by BOTH the "Blocked" and "Walked Away" tiles if
 * anything counted that column raw. Nothing does: every displayed "Blocked" number goes through
 * [com.astraedus.nudge.data.db.entity.isShownConfrontation] or its SQL mirror in `UsageEventDao`,
 * both of which exclude exactly this row. Change `wasBlocked` here and those counts silently start
 * reporting walk-aways as blocks.
 */
@Singleton
class RecordWalkAwayUseCase @Inject constructor(
    private val usageRepository: UsageRepository,
    private val logger: NudgeLog
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logger.e("walk-away NOT recorded: insert failed", throwable)
        }
    )

    /**
     * Fire-and-forget the walk-away write on the process-scoped IO scope, so the caller can finish
     * immediately without the row depending on the caller still being alive.
     */
    fun record(packageName: String, blockMode: String) {
        scope.launch { recordNow(packageName, blockMode) }
    }

    /** The write itself. `internal` so a test can await it instead of racing a fire-and-forget. */
    internal suspend fun recordNow(packageName: String, blockMode: String) {
        usageRepository.logEvent(buildEvent(packageName, blockMode))
        logger.i("walk-away recorded package=$packageName mode=$blockMode")
    }

    internal companion object {
        /**
         * The row a walk-away writes. `wasBlocked = true` because the user really was blocked —
         * they hit the overlay and turned around; `userChangedMind = true` is what separates that
         * from giving in and waiting.
         */
        fun buildEvent(packageName: String, blockMode: String): UsageEvent = UsageEvent(
            packageName = packageName,
            wasBlocked = true,
            blockMode = blockMode,
            userChangedMind = true
        )
    }
}
