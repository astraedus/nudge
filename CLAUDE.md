# Nudge — Open-Source Android App Blocker

Privacy-first app blocker with delay-to-open (breathing exercises before opening distracting apps), per-app daily time budgets, app groups, schedule-based rules, in-app feature blocking (YouTube Shorts, Instagram Reels, TikTok), and grayscale mode. Zero internet permission. All data local.

- GitHub: https://github.com/astraedus/nudge
- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38398
- Current version and full release history: see `CHANGELOG.md`

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug                    # Build debug APK
./gradlew assembleRelease                  # Build release APK (needs keystore.properties)
./gradlew test                             # Unit tests (JVM)
./gradlew connectedAndroidTest             # Instrumented tests (needs device)
adb install -r app/build/outputs/apk/debug/app-debug.apk  # Install on device
```

Test device: Pixel 3 on ADB at `192.168.1.68:5555` (Android 12, API 31).

**Gradle version: stay on 8.x.** Do NOT upgrade to Gradle 9.x -- it removed `JvmVendorSpec.IBM_SEMERU` which the React Native / Android Gradle plugins still reference. Gradle 8.13 is the latest compatible version, and we are on it (AGP 8.13.2, compile/target SDK 36).

## Releasing

Two paths: fast (instant) or CI (verified).

**Fast path** -- release is live in seconds, CI verifies in the background:
```bash
# 1. Bump version in app/build.gradle.kts (versionCode + versionName)
# 2. Build locally: ./gradlew test && ./gradlew assembleRelease
# 3. Commit, tag, push
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.4.0"
git tag v1.4.0
git push origin main --tags
# 4. Create release immediately with local release APK
cp app/build/outputs/apk/release/app-release.apk nudge-v1.4.0.apk
gh release create v1.4.0 nudge-v1.4.0.apk --title "v1.4.0" --generate-notes
```

**CI-only path** -- just tag and push, wait ~4 min for GitHub Actions:
```bash
git tag v1.4.0
git push origin main --tags
# GitHub Action builds release APK, tests, creates release automatically
```

CI runs on every tag push (`.github/workflows/release.yml`). Builds `assembleRelease` (APK) **and `bundleRelease` (AAB)** using secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`); both are attached to the GitHub Release (APK for direct download + F-Droid; AAB for Google Play). Also exposes `workflow_dispatch` so a Play-ready AAB can be rebuilt from `main` without re-tagging. If a release already exists (fast path), CI updates it.

**Every push to `main`** (added 2026-07-05) also auto-builds the signed APK (tests-gated by `./gradlew test`) and publishes/refreshes a rolling **`main-latest` PRERELEASE** (`…/releases/tag/main-latest`, always the newest main) — an installable dev build per merge, asset `nudge-main.apk`. NOT a versioned release; `v*` tags remain the real releases. Watch: `gh run list --repo astraedus/nudge --branch main`.

### Google Play release (catch Play up after a GitHub release)

GitHub releases are automatic; **Google Play is a separate, deliberate step** that runs **from the laptop, not CI**:

```bash
# Default: live to 100% of production users. This is the standing default.
scripts/publish-to-play.sh 1.7.0
# Opt in to a staged, halt-able rollout — only for genuinely risky releases,
# and you then OWE the promote step (see below).
STATUS=inProgress ROLLOUT=0.2 scripts/publish-to-play.sh 1.7.0
# Upload as a production DRAFT (no users affected) to eyeball it first.
STATUS=draft scripts/publish-to-play.sh 1.7.0
```

**Full rollout is the default (Anti, 2026-07-30: "for google play it's just easier that way").** A staged rollout is a second owed step days later, and the promotion can't be done by this script — so twice running (v1.9.4, v1.10.0) the tail step was forgotten or cost time, for ~no signal at our install base. Stage only when a release is genuinely risky, and file the promote as a dated task when you do.

**Promoting a staged/draft release later** — `publish-to-play.sh` CANNOT do it (`gplay release` re-uploads the AAB; Play rejects an existing versionCode), and **`gplay rollout complete` is also broken** — it sets `status=completed` but leaves `userFraction`, which Play rejects with `COMPLETED release must not have fraction`. Use the edit cycle:

```bash
EDIT=$(gplay edits create --package dev.astraedus.nudge | jq -r '.id')
gplay tracks get --package dev.astraedus.nudge --edit "$EDIT" --track production \
  | jq '[ .releases[] | select(.name=="X.Y.Z") | (.status="completed" | del(.userFraction)) ]' > /tmp/rel.json
gplay tracks update --package dev.astraedus.nudge --edit "$EDIT" --track production --releases @/tmp/rel.json
gplay edits validate --package dev.astraedus.nudge --edit "$EDIT"
gplay edits commit   --package dev.astraedus.nudge --edit "$EDIT"
gplay status --package dev.astraedus.nudge --pretty   # verify
```
Submit ONLY the release being promoted — the superseded one drops off the track automatically.

The script pulls the CI-built AAB from the GitHub Release (or `SOURCE=run` for a `workflow_dispatch` artifact), runs `gplay preflight` (offline secret/compliance scan), then `gplay release` to the chosen track with release notes auto-extracted from `CHANGELOG.md`.

**Why local, not CI (open-source security):** the repo is PUBLIC, so we never put the Google Play API credential in GitHub Actions — a malicious PR or compromised action could exfiltrate it. CI only ever holds the **upload key** (`KEYSTORE_BASE64`), and because Nudge is enrolled in **Play App Signing** (mandatory for apps first published after Aug 2021), Google holds the real app-signing key — a leaked upload key can be rotated in Play Console without bricking installed users. The powerful `gplay` admin service-account key stays on the laptop (chmod 600, gitignored). To go fully tag-triggered later, create a **dedicated, least-privilege** Play service account (Nudge-only, "release manager" — never the account admin key) and store it as a GitHub secret; only then is CI-side Play upload acceptable. Ref: `~/ops/references/play-console-cli.md`.

> Play track state is queryable: `gplay status --package dev.astraedus.nudge --pretty`. As of the AAB-pipeline addition, Play production was on 1.5.6 (versionCode 27) while the repo was at 1.7.0 (versionCode 31) — i.e. Play had drifted 4 versions behind because the push was manual. This script closes that gap.

## Release Signing

Keystore: `nudge-release.keystore` (PKCS12, alias `nudge`, 2048-bit RSA, 10000-day validity).
Config: `keystore.properties` (gitignored). CI uses GitHub secrets.
**Always use `assembleRelease`** for distribution. Debug APKs use machine-specific keys and cause "App not installed" when users try to update from a different build.

## Architecture

Clean Architecture in a single module with package boundaries:

```
com.astraedus.nudge/
├── data/           # Room DB, DAOs, repositories, DataStore preferences
├── domain/         # Pure Kotlin models, BlockEngine, use cases (NO Android deps)
├── service/        # AccessibilityService, ForegroundService, GrayscaleManager
├── ui/             # Jetpack Compose screens, navigation, overlay, theme
└── di/             # Hilt modules
```

**Dependency direction**: `ui` -> `domain` <- `data`, `service` -> `domain`. Domain has no Android imports.

### Core flow

```
AccessibilityService: TYPE_WINDOW_STATE_CHANGED
  (or a state-verified TYPE_WINDOW_CONTENT_CHANGED — see "Content-change app-switch fallback"
   in docs/architecture/foreground-detection.md)
  -> BlockEngine.evaluate(packageName, time, usage)
  -> BlockDecision: ALLOW | HARD_BLOCK | DELAY | BREATHING
  -> Launch BlockOverlayActivity if not ALLOW
```

## Stack

- Kotlin, Jetpack Compose, Material 3
- Hilt (DI), Room (DB), DataStore (preferences)
- Coroutines + Flow
- Min SDK 26, Target SDK 36, Compile SDK 36

## Key conventions

- Domain layer is pure Kotlin — no `android.*` imports. Fully unit-testable on JVM.
- Single Activity architecture (MainActivity) + Compose Navigation.
- BlockOverlayActivity is a separate activity with `singleInstance` launch mode, `excludeFromRecents`, empty `taskAffinity`.
- AccessibilityService handles foreground app detection AND every enforcement decision, so if it stops, blocking stops completely. `NudgeMonitorService` holds process priority and the ongoing notification, and polls `ServiceHealth` so that notification stops claiming Nudge is active when it is not; it reads no rules, evaluates nothing, and must NEVER start an Activity (pinned by `MonitorServiceContractTest`, because "a service put its UI over another app" is a bug report this repo has already received once). Both that poll and `ProtectionWatchdogWorker` reach one shared `ProtectionCheck`, which owns the single alert that tells the user blocking has stopped. See `docs/architecture/service-lifecycle-and-watchdog.md`.
- All entities use Room `@Entity` annotations. DAOs return `Flow<>` for reactive queries.
- ViewModels use `@HiltViewModel` and inject use cases/repositories.
- No internet permission. No analytics. No telemetry. `allowBackup="false"` + `res/xml/data_extraction_rules.xml` / `backup_rules.xml` excluding every domain on both transports: Auto Backup would otherwise upload the database to Google Drive, and running a backup force-kills the process. Export/import is the only backup path.

## Block modes

- `HARD_BLOCK` — cannot open the app at all
- `DELAY` — configurable countdown (5/15/30/60s) before app opens
- `BREATHING` — guided breathing exercise before app opens (the signature feature)

## Database

Room DB version 10. Migrations: 1->2 (schedule/inapp/grayscale), 2->3 (userChangedMind), 3->4 (showCounter), 4->5 (autoKickAfter), 5->6 (showTimeRemaining, autoKickCooldownSeconds), 6->7 (webDomains), 7->8 (autoKickAfterMinutes), **8->9 (DROPS the dead `usage_events.durationMs` column, issue #22)**, 9->10 (`BlockRule.webBlockMode`, issue [#21](https://github.com/astraedus/nudge/issues/21); also repairs the rows that bug created).

`NudgeDatabaseMigrationTest` is a **JVM** test (a `SupportSQLiteDatabase` `Proxy` records the `execSQL` calls), not an instrumented one — so migrations are gated by `./gradlew test` with no device. It also asserts every version gap from 1 to the current version has a registered migration, which is what catches "bumped the version, forgot `DatabaseModule.addMigrations`".

8->9 is the only migration that is not an `ALTER TABLE … ADD COLUMN`; the create/copy/drop/rename recreate, and the two constraints on anyone touching it, are in `docs/architecture/export-import.md`.

## Subsystem deep-dives (read BEFORE touching that area)

Each doc is the full, unabridged history of that subsystem: why it is shaped the way it is, which bugs
produced which invariant, and what the tests are pinning. They are NOT loaded automatically; open the
one that matches what you are about to edit. Nothing here is optional reading if you are changing that code.

| Doc | Scope | Read before |
|---|---|---|
| `docs/architecture/rules-and-features.md` | Schedules, in-app feature blocking (Shorts/Reels/TikTok), grayscale, editable overlay messages, rule editor | editing rule models, `InAppDetector`, `NudgeMessages`, the rule editor, Settings |
| `docs/architecture/counter-overlay-and-autokick.md` | Interaction counter, time-remaining overlay, both auto-kick triggers, cooldown, duration inputs | editing `service/` overlay code, `InteractionTracker`, `CounterCacheRefresher`, `AutoKick*`, `DurationInput` |
| `docs/architecture/foreground-detection.md` | What "the user is in app P" means: transient windows, the Home/launcher path, the content-change fallback, picture-in-picture, passthrough clearing | touching the event dispatch in `NudgeAccessibilityService`, `PassthroughManager`, or adding ANY early return to the hot path |
| `docs/architecture/block-overlay-lifecycle.md` | Overlay lifecycle invariant (#8), the walk-away path, the daily 2-minute pass | editing `ui/overlay/`, `RecordWalkAwayUseCase`, `EmergencyPass*` |
| `docs/architecture/service-lifecycle-and-watchdog.md` | What keeps enforcement alive and what notices when it dies: FGS start paths, the `MY_PACKAGE_REPLACED` receiver, the WorkManager watchdog + protection alert, live permission state | editing `NudgeMonitorService`, `BootReceiver`, `ProtectionWatchdogWorker`, `ProtectionStatus`, `NudgeApp`, `MainActivity`, or the Settings permission rows |
| `docs/architecture/strict-mode.md` | Commitment lock, OS escape-route guard, global master toggle gating | editing `domain/lock/`, `ui/lock/`, or any gate in `onAccessibilityEvent` |
| `docs/architecture/web-domain-blocking.md` | Per-rule website blocking, multi-browser URL-bar reads, independent `webBlockMode` (#21) | editing `WebDomainMatcher`, `WebDomainDetector`, `WebBlockMode`, or the browser paths |
| `docs/architecture/content-filter.md` | Bundled blocklist + keyword layer, the curation policy, the 274k-blob incident | touching `content_filter_domains.txt`, `ContentFilterRepository`, `ContentFilterMatcher`, or the keyword lists |
| `docs/architecture/export-import.md` | Backup format (rules/groups/history/settings), per-entry failure policy, the import Strict-Mode gate, the DB 8→9 recreate | editing `data/export/`, `ImportRulesUseCase`, `applyImportedSettings`, `MIGRATION_8_9` |
| `docs/architecture/stats-and-charts.md` | Dashboard tiles + mini charts, stats screen, day selection, the one screen-time source, insight pages | editing `ui/screens/stats/`, `HomeChartsBuilder`, `InsightsCalculator`, `ScreenTimeProvider`, chart geometry |
| `docs/TESTING.md` | Testing philosophy, principles, coverage targets | deciding what a change owes in tests |
| `docs/play-store.md` | Play a11y prominent-disclosure policy, store listing assets | touching onboarding, the Settings permission flow, or the listing |
| `docs/BACKLOG.md` | Known issues, in-progress work, future ideas | picking up work, or after finding a new defect |
| `tasks/lessons.md` | Mistakes already made in THIS repo | starting any non-trivial change |

## Testing (never regress)

**Every new feature MUST ship with tests, and `./gradlew test` must pass before every commit.** If tests fail,
the feature isn't done. When fixing a bug, write the test that reproduces it FIRST. Regression = blocker.
The domain layer is pure Kotlin with no Android deps, so it is fast to JVM-test and carries the highest bar.

- `app/src/test/`, JVM unit tests (domain, data, use cases)
- `app/src/androidTest/`, instrumented tests (Room migrations, accessibility service behavior)

Full principles + coverage targets: `docs/TESTING.md`.

## Post-feature checklist

After any feature addition or significant change:
1. Write tests covering the new behavior (unit + integration as appropriate)
2. Run `./gradlew test` and verify ALL tests pass (not just new ones)
3. Build debug APK: `./gradlew assembleDebug`
4. Install on Pixel 3: `adb -s 192.168.1.68:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
5. **QA on device** — spawn `device-tester` agent with specific test cases. PASS required before push.
6. If QA passes: bump `versionCode` + `versionName` (patch) in `app/build.gradle.kts`
7. Update CHANGELOG.md with version + date + changes
8. Update this CLAUDE.md (architecture docs, feature descriptions) if applicable
9. Commit all changes, tag, push: `git push origin main --tags`
10. Create GitHub release (fast path): `gh release create vX.Y.Z nudge-vX.Y.Z.apk --title "vX.Y.Z" --generate-notes`
11. **Publish to Google Play** — the standing default so Play never drifts behind GitHub again. After CI attaches the AAB, run `scripts/publish-to-play.sh X.Y.Z` — that ships to **100% of production** in one step, no follow-up owed. Stage (`STATUS=inProgress ROLLOUT=0.2 …`) only for a genuinely risky release, and file the promote step as a dated task if you do. Play credentials stay on the laptop — never in CI. See the **Releasing → Google Play** section for the security rationale.
12. Update store listing copy if user-facing

**This is the standard ship flow. Every change that touches user-facing behavior gets a device QA gate before push.**

**SHIP AUTONOMOUSLY — do NOT ask for permission once the device-QA gate passes.** This is a documented, reversible, owned release flow (own-the-last-mile rule). When QA is green: bump the version, update CHANGELOG/docs, commit, build `assembleRelease`, tag, `git push origin main --tags`, and `gh release create` — end to end, no confirmation step. Asking "should I push?" on a verified change is the exact anti-pattern this repo's flow exists to prevent. The ONLY things that still warrant a pause are the universal ones: money, real-world identity, known-contact email, ban-risk platform actions, or destructive/irreversible deletion — none of which a Nudge release involves.

## Google Play compliance and store listing

The AccessibilityService **prominent disclosure** dialog is a Play policy gate this app has already been
rejected on, read `docs/play-store.md` before touching onboarding or the Settings permission flow.
Listing assets live in `store-listing/`.

## Backlog

Known issues, in-progress work and future ideas: `docs/BACKLOG.md`.
Mistakes already made in this repo: `tasks/lessons.md`.
