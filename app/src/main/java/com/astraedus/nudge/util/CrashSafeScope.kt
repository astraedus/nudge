package com.astraedus.nudge.util

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * A long-lived [CoroutineScope] whose children cannot take the process down with them.
 *
 * ## Why this exists (backlog audit F7)
 *
 * `SupervisorJob()` is the half of the answer everyone remembers: it stops one failed child
 * cancelling its siblings. It does NOTHING about the exception itself. An unhandled throwable in a
 * root coroutine reaches `handleCoroutineException`, which, with no [CoroutineExceptionHandler] in
 * the context, hands it to the thread's default uncaught-exception handler -- and that kills the
 * process.
 *
 * For `NudgeAccessibilityService.serviceScope` that is not an abstract risk. Every block
 * evaluation, every `UsageEvent` write and every DataStore collect in the app runs there, on the
 * highest-traffic path this app has. One Room constraint violation, one binder death, one
 * `IllegalStateException` from a DataStore read, and the process dies -- taking the accessibility
 * service with it, which means blocking stops entirely until the system rebinds. Losing a stat row
 * must never stop enforcement.
 *
 * `RecordWalkAwayUseCase` already carries this exact pattern, and the comment on it already
 * records the incident that put it there. This is that pattern as one testable thing, so the app's
 * busiest scope gets the same guarantee and a JVM test can prove it rather than a device session.
 *
 * @param name appears in the log line, so "which scope died" is answerable from a bug report.
 * @param logger a lambda, not a value: the accessibility service builds its scope as a field and
 *   resolves its logger through a lazy Hilt entry point, so the logger must not be touched until
 *   something actually throws.
 */
object CrashSafeScope {

    fun create(
        name: String,
        dispatcher: CoroutineDispatcher,
        logger: () -> NudgeLog
    ): CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName(name) +
            CoroutineExceptionHandler { context, throwable ->
                val coroutine = context[CoroutineName]?.name ?: name
                logger().e("unhandled exception in $coroutine, swallowed to keep the service alive", throwable)
            }
    )
}
