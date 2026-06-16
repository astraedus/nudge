# Nudge -- Project Lessons

## Stats display: "today only" looks like data loss (2026-05-20)

HomeScreen stats (Blocked, Walked Away) were filtered to today only. Users updating the app (often at start of day) saw 0 stats and thought the update wiped their data. No actual data loss -- all Room migrations are additive ALTER TABLE, no `fallbackToDestructiveMigration()` or `clearAllTables()` anywhere.

Fix: Added "All Time" stats alongside "Today" on the home screen. When adding new stats, always consider whether the user needs both a time-scoped view and a cumulative view.

## cleanup() is dead code (2026-05-20)

`UsageRepository.cleanup()` exists but is never called anywhere. No scheduled cleanup, no startup cleanup. If we add cleanup later, verify the retention window doesn't surprise users (30 days default).

## Migration test must track currentVersion (2026-05-20)

`NudgeDatabaseMigrationTest` was stuck at `currentVersion = 6` while the DB was at version 7. When adding a new migration, always update the test's `allMigrations` list AND `currentVersion`.

## AccessibilityNodeInfo needs mockk in JVM tests (2026-06-16)

`WebDomainDetectorTest` originally only tested `isBrowser`/null cases because reading `AccessibilityNodeInfo` (Android framework class) throws "not mocked" in plain JVM tests. To test `detectUrl`'s node reads, add `testImplementation("io.mockk:mockk:1.13.13")` and `mockk<AccessibilityNodeInfo>(relaxed = true)`. Better still: extract the pure logic (e.g. `urlBarViewIdsFor()` id resolution) so most coverage needs no Android mocking at all.

## Content filter framing is a hard constraint (2026-06-16)

The web content filter blocks adult sites but MUST stay generically framed everywhere user-visible: setting title "Block restricted websites", overlay rule name "Restricted content". Blocklist (`assets/content_filter_domains.txt`) + `DEFAULT_KEYWORDS` live only in code/assets. When grepping for accidental leaks, note `hasExisting`/`hasExceeded` are false-positive substring hits for "sex"/"xxx", exactly the ambiguous-token class the keyword list avoids.
