package com.astraedus.nudge.domain.health

/**
 * The one place a [ServiceHealth] becomes words the user reads.
 *
 * Separate from the notification code because the defect this subsystem exists to fix was a
 * *sentence* ("Nudge is active" while Nudge was not active), not a mechanism, and a sentence that
 * lives inside an Android class is a sentence no JVM test can read. Every state is spelled out here
 * with no `else` branch, so adding a state to [ServiceHealth] fails to compile rather than falling
 * into a default that says the reassuring thing.
 */
data class ServiceHealthCopy(val title: String, val body: String)

/**
 * Copy for the ongoing foreground-service notification.
 *
 * [ServiceHealth.DISABLED] has copy even though the service stops itself in that state: the value
 * has to exist for the exhaustiveness test to mean anything, and a stop is not instantaneous.
 */
fun ServiceHealth.notificationCopy(): ServiceHealthCopy = when (this) {
    ServiceHealth.DISABLED -> ServiceHealthCopy(
        title = "Nudge is off",
        body = "Blocking is turned off"
    )
    ServiceHealth.PERMISSION_MISSING -> ServiceHealthCopy(
        title = "Nudge is not blocking",
        body = "Accessibility access is off. Tap to turn it on."
    )
    ServiceHealth.STOPPED_BY_SYSTEM -> ServiceHealthCopy(
        title = "Nudge is not blocking",
        body = "Your phone stopped Nudge. Tap to turn accessibility off and on again."
    )
    ServiceHealth.ACTIVE -> ServiceHealthCopy(
        title = "Nudge is active",
        body = "Blocking is on"
    )
}
