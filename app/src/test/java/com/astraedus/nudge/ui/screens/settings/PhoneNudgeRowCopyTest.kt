package com.astraedus.nudge.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The escalation line under the first-pause picker. Pure, so the copy is pinned. */
class PhoneNudgeRowCopyTest {

    @Test
    fun `the escalation preview spells out the later pauses`() {
        assertEquals("Then 10 s, 20 s, 30 s, 30 s", escalationPreview(5))
        assertEquals("Then 20 s, 30 s, 30 s, 30 s", escalationPreview(10))
        assertEquals("Then 60 s, 60 s, 60 s, 60 s", escalationPreview(60))
    }
}
