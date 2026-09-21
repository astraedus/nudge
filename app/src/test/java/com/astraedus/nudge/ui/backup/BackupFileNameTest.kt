package com.astraedus.nudge.ui.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The name the save picker offers. It is the first thing the user sees of their backup and the
 * only label it carries in a folder a year later, so it is pinned rather than eyeballed.
 */
class BackupFileNameTest {

    @Test
    fun `a backup is named for the day it was taken`() {
        assertEquals("nudge-backup-2026-09-21.json", backupFileName(LocalDate.of(2026, 9, 21)))
    }

    /** Zero-padded ISO, so a folder of backups sorts into the order they were taken. */
    @Test
    fun `backups from different days sort chronologically by name`() {
        val names = listOf(
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 1, 9),
            LocalDate.of(2025, 11, 30)
        ).map(::backupFileName)

        assertEquals(names.sorted(), names.sortedBy { it.removePrefix("nudge-backup-") })
        assertEquals("nudge-backup-2025-11-30.json", names.min())
    }

    @Test
    fun `the name carries the json extension the save picker filters on`() {
        assertTrue(backupFileName(LocalDate.of(2026, 9, 21)).endsWith(".json"))
        assertEquals("application/json", BACKUP_MIME_TYPE)
    }
}
