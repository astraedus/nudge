# Nudge -- Project Lessons

## Stats display: "today only" looks like data loss (2026-05-20)

HomeScreen stats (Blocked, Walked Away) were filtered to today only. Users updating the app (often at start of day) saw 0 stats and thought the update wiped their data. No actual data loss -- all Room migrations are additive ALTER TABLE, no `fallbackToDestructiveMigration()` or `clearAllTables()` anywhere.

Fix: Added "All Time" stats alongside "Today" on the home screen. When adding new stats, always consider whether the user needs both a time-scoped view and a cumulative view.

## cleanup() is dead code (2026-05-20)

`UsageRepository.cleanup()` exists but is never called anywhere. No scheduled cleanup, no startup cleanup. If we add cleanup later, verify the retention window doesn't surprise users (30 days default).

## Migration test must track currentVersion (2026-05-20)

`NudgeDatabaseMigrationTest` was stuck at `currentVersion = 6` while the DB was at version 7. When adding a new migration, always update the test's `allMigrations` list AND `currentVersion`.
