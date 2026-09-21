package com.astraedus.nudge.ui.backup

import java.time.LocalDate

/** Media type of a backup file, used for both the save picker and the share intent. */
const val BACKUP_MIME_TYPE = "application/json"

/**
 * The name a backup file is offered under: `nudge-backup-2026-09-21.json`.
 *
 * Dated, because a backup is something people keep several of -- a fixed name means the second one
 * either overwrites the first or arrives as "nudge-backup(1).json", and neither tells the user
 * which phone or which week it came from. ISO order so the folder sorts chronologically.
 *
 * Pure, and takes [today], so the shape is a unit test rather than something to eyeball on a
 * device.
 */
fun backupFileName(today: LocalDate = LocalDate.now()): String = "nudge-backup-$today.json"
