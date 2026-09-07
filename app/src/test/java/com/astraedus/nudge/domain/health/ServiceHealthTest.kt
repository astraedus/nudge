package com.astraedus.nudge.domain.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole truth table, because the bug this replaces was a constant string.
 *
 * The case that matters, and the one no previous test could have expressed, is
 * [permission granted but service not connected]: that is the state the Pixel sat in after the
 * 01:59 backup kill on 2026-09-07 while the notification said "Nudge is active".
 */
class ServiceHealthTest {

    @Test
    fun `master toggle off outranks everything`() {
        // Even a perfectly healthy service is DISABLED when the user has switched Nudge off — a
        // disabled Nudge must behave as if uninstalled, notification included.
        assertEquals(
            ServiceHealth.DISABLED,
            ServiceHealth.evaluate(
                globalEnabled = false,
                permissionGranted = true,
                serviceConnected = true
            )
        )
        assertEquals(
            ServiceHealth.DISABLED,
            ServiceHealth.evaluate(
                globalEnabled = false,
                permissionGranted = false,
                serviceConnected = false
            )
        )
    }

    @Test
    fun `no accessibility permission is reported as not blocking`() {
        assertEquals(
            ServiceHealth.PERMISSION_MISSING,
            ServiceHealth.evaluate(
                globalEnabled = true,
                permissionGranted = false,
                serviceConnected = false
            )
        )
    }

    @Test
    fun `granted but unbound is STOPPED_BY_SYSTEM — the post-kill window`() {
        // Settings still lists Nudge (permissionGranted), our instance is gone (not connected).
        // Nothing is enforced and only this state can say so.
        assertEquals(
            ServiceHealth.STOPPED_BY_SYSTEM,
            ServiceHealth.evaluate(
                globalEnabled = true,
                permissionGranted = true,
                serviceConnected = false
            )
        )
    }

    @Test
    fun `granted and bound is ACTIVE`() {
        assertEquals(
            ServiceHealth.ACTIVE,
            ServiceHealth.evaluate(
                globalEnabled = true,
                permissionGranted = true,
                serviceConnected = true
            )
        )
    }

    @Test
    fun `only the two not-blocking-but-asked-to states are degraded`() {
        assertTrue(ServiceHealth.PERMISSION_MISSING.isDegraded)
        assertTrue(ServiceHealth.STOPPED_BY_SYSTEM.isDegraded)
        assertFalse(ServiceHealth.ACTIVE.isDegraded)
        // DISABLED is not degraded: the user asked for nothing, so there is nothing to alert about.
        assertFalse(ServiceHealth.DISABLED.isDegraded)
    }

    /**
     * The regression this file exists for, stated as a property rather than a case: no state in
     * which Nudge is not enforcing may carry reassuring copy. "Nudge is active" was shown for
     * hours while blocking was down; that sentence may only appear for [ServiceHealth.ACTIVE].
     */
    @Test
    fun `a non-enforcing state never claims to be active`() {
        ServiceHealth.entries.forEach { health ->
            val copy = health.notificationCopy()
            if (health != ServiceHealth.ACTIVE) {
                assertFalse(
                    "$health must not claim Nudge is active: ${copy.title} / ${copy.body}",
                    copy.title.contains("is active", ignoreCase = true)
                )
            }
        }
        assertEquals("Nudge is active", ServiceHealth.ACTIVE.notificationCopy().title)
    }

    /**
     * Every state gets its own words. A `when` with an `else` would compile and would quietly give
     * a new state the reassuring default — the same shape as the one-error-string-for-two-causes
     * defect this repo has already paid for.
     */
    @Test
    fun `every state has distinct, non-blank copy`() {
        val copies = ServiceHealth.entries.map { it.notificationCopy() }
        copies.forEach {
            assertTrue("title must not be blank", it.title.isNotBlank())
            assertTrue("body must not be blank", it.body.isNotBlank())
        }
        assertEquals(
            "each state needs its own body — a shared string cannot tell the user what to do",
            ServiceHealth.entries.size,
            copies.map { it.body }.distinct().size
        )
    }

    /**
     * A degraded state's copy must tell the user what to DO. "Nudge is not blocking" alone leaves
     * them where Settings already left them: knowing something is wrong, not knowing the fix.
     */
    @Test
    fun `degraded copy names the recovery action`() {
        ServiceHealth.entries.filter { it.isDegraded }.forEach { health ->
            val body = health.notificationCopy().body.lowercase()
            assertTrue(
                "$health must tell the user to tap: $body",
                body.contains("tap")
            )
            assertTrue(
                "$health must name accessibility as the thing to fix: $body",
                body.contains("accessibility")
            )
        }
    }
}
