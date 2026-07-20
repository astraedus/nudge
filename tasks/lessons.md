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

## Play draft→full: don't run publish-to-play.sh twice (2026-07-19)

`scripts/publish-to-play.sh <ver>` UPLOADS the AAB on every run. The documented two-step (draft to verify, then `STATUS=completed ROLLOUT=1.0 …` for full rollout) fails on the second run with `Error 403: Version code N has already been used` — the draft already consumed that versionCode. To promote an already-staged draft to a completed full rollout WITHOUT re-uploading: `gplay edits create` → `gplay tracks update --edit <id> --track production --releases '[{"status":"completed","versionCodes":["N"],"releaseNotes":[…]}]'` → `gplay edits validate` → `gplay edits commit`. (The single completed release supersedes the prior one automatically.) Better: for a confident release, skip the draft step and run the full-rollout invocation once. NB: the script truncates CHANGELOG release notes at ~500 chars mid-word — for a clean Play "What's new", pass hand-written notes in the `tracks update` releases JSON.

## Content filter framing is a hard constraint (2026-06-16)

The web content filter blocks adult sites but MUST stay generically framed everywhere user-visible: setting title "Block restricted websites", overlay rule name "Restricted content". Blocklist (`assets/content_filter_domains.txt`) + `DEFAULT_KEYWORDS` live only in code/assets. When grepping for accidental leaks, note `hasExisting`/`hasExceeded` are false-positive substring hits for "sex"/"xxx", exactly the ambiguous-token class the keyword list avoids.

## A transient GitHub API 5xx must not fail the Release run (2026-07-20)

Release run 29710052363 failed on a docs-only commit: the "Publish rolling dev build (main)" step of `.github/workflows/release.yml` hit `error checking for existing release: HTTP 503` from `gh release create`. The build, tests, AAB/APK, and artifact upload had ALL succeeded -- only the convenience `main-latest` publish flaked, and it had no retry, so one momentary GitHub API blip sank the whole run.

Fix: the rolling-tag publish is now an idempotent `publish_rolling_build()` (delete-then-create) retried with exponential backoff (5 attempts). Lesson for any CI step that calls a flaky external API: wrap network mutations in a retry-with-backoff; make them idempotent (a failed delete is re-attempted before create); and let a *persistent* failure still exit non-zero so real breakage stays loud. Do NOT reach for `continue-on-error` -- that would swallow genuine failures too.
