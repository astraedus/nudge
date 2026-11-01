# Plan: Groups PR 4 (All apps except)

Spec: `docs/exec-plans/active/groups-categories-allowlist.md`, sections 1, 3.2-3.6, 3.8, 3.9, 4, 5.1,
5.4-5.8, 6.1, "7 -> PR 4", 8. In scope for When: "All the time" and "Days and times" only (first
unlock is PR 5b). Base: main at v1.24.0 (PR 3).

## Day-0 contracts (lane-pr4, committed before fan-out)

- DB 13 -> 14: `block_rules.allApps`, `exceptPackages` (CSV), `exceptGroupIds` (CSV), registered in
  `DatabaseModule`, gap covered by `NudgeDatabaseMigrationTest`.
- `RuleTarget` (App / Group / AllApps, `of()`, `isWellFormed`, `ExceptCsv`), `AllAppsBlock`,
  `BlockRuleData`/`ActiveRule` fields, `RuleCoverage` (spec 4.1) + `CoverageFacts(essential,
  launchable)` with no default, `EssentialPackages` port (`current()`, `cachedOrNull()`).
- `RuleEvaluator.resolveRulesForPackage` filters through `RuleCoverage.covers`; rule name "All apps".
- `EvaluateBlockUseCase` reads the two device facts lazily (`RuleCoverage.factsNeeded`), only when a
  rule needs them, so app-rule-only setups pay nothing.
- `BlockEngine` fills `Block.until` (deciding rule's window end) and `Block.allApps`; the service
  forwards them as `EXTRA_UNTIL` / `EXTRA_ALL_APPS_*` to the overlay.
- `CoveredApps` / Home "Active Apps" count include All apps coverage.
- `ScheduleEvaluator.windowEndAt`, `RuleSummary.allAppsTitle` / `untilText`.
- DAO/repo: `getAllAppsRules()`, `getRule(id)`, `saveAllAppsRule(rule)`.
- Route `Screen.AllAppsEditor` (`ruleId`, -1 = new) with stub screen + ViewModel.

## Lanes (disjoint files)

| Lane | Files | Model |
|---|---|---|
| A. Engine + service tests | `domain/engine/*`, `domain/usecase/EvaluateBlockUseCase.kt`, `service/NudgeAccessibilityService.kt`, `service/EssentialApps.kt`, their tests, `docs/architecture/rule-snapshot.md`, `docs/architecture/web-domain-blocking.md`, `docs/architecture/nuke-mode.md` | opus |
| B. Data + Strict + export | `data/repository/BlockRuleRepository.kt`, `data/db/dao/*`, `domain/lock/*`, `data/export/*`, `domain/usecase/ImportRulesUseCase.kt`, `domain/usecase/ExportRulesUseCase.kt`, their tests, `docs/architecture/strict-mode.md`, `docs/architecture/export-import.md` | opus |
| C. All apps editor + picker groups | `ui/screens/allapps/*` (new), `ui/components/AppPicker.kt`, their tests | opus |
| D. Manage Apps, sheet, App config, group editor, Home card | `ui/screens/apps/*`, `ui/screens/config/*`, `ui/screens/groups/*`, `ui/screens/home/*`, `ui/components/rule/RuleSummary.kt`, their tests, `docs/architecture/rules-and-features.md`, `docs/architecture/stats-and-charts.md` | sonnet |
| E. Block screen | `ui/overlay/*`, their tests, `docs/architecture/block-overlay-lifecycle.md` | sonnet |
| Integration | `ui/navigation/NavGraph.kt` (callbacks added by C/D with `= {}` defaults), CHANGELOG, CLAUDE.md, `tasks/lessons.md` | lane-pr4 |

## Decisions

- **Rule name.** The engine names an All apps rule "All apps" (the domain has no labels). The overlay
  builds the visible title with labels: `RuleSummary.allAppsTitle(names)` = "All apps except Spotify,
  Maps" (two names, then `+N`), groups first.
- **Essentials are never covered by a group or All apps rule; an explicit app rule on an essential
  app is honoured** (spec 4.2). One list, `EssentialApps`, read by Nuke, coverage, the editor's
  "Always allowed" sheet and the picker.
- **Stale group ids** in `exceptGroupIds` allow nothing (fail strict). `deleteGroupWithRules` strips
  the id from every rule in the same transaction.
- **Strict Mode, window edits (brief vs spec 5.6/F6).** The spec leaves days-and-times edits free
  (F6). The PR 4 brief says shortening the window challenges. Resolution: for **All apps rules only**,
  a schedule change whose new active set is not a superset of the old one is weakening. Scheduled ->
  "All the time" is stricter (free); "All the time" -> scheduled is weakening. App and group rules
  keep today's behaviour (F6 unchanged).
- **"All the time" + Hard block** confirms on save: "Block N apps all the time? Only the apps you
  listed and your essentials will open." [Block them] [Cancel]. No other combination is confirmed.
- **Keep the pass on short trips** is hidden and forced off on All apps rules; auto-kick, reminder,
  counter, grayscale, daily limit, web domains and features are not offered (spec 3.4).
- **Browsers (5.5):** an All apps rule covers a browser like any launchable app (it is in "Blocks N
  apps"); a covered browser is evaluated as an app first. The service's sync check fails closed.
- **Block screen:** an All apps block hides the redirect bubble and shows "Open an allowed app" with
  up to four launchable allowed apps. "Until 8:42 AM" shows for every scheduled rule.
- **Home lock card:** shown only while an All apps rule is active now; several active -> the one that
  ends last ("All the time" ends last).

## Tests (spec "7 -> PR 4")

- L1 `RuleCoverageTest` (covered / essential / not launchable / allowed app / allowed group / joins
  later / stale id), the rules-only-add invariant over every `RuleTarget` kind, `RuleWeakeningTest`
  (except lists grew/shrank, All apps window), `GroupWeakeningTest` for every `GroupUsage`,
  `windowEndAt`, editor count + "also blocked by" helpers.
- L2 browser under an All apps rule (replay).
- L3 a nuked allowed app is still nuked; zero DAO reads per evaluation once warm.
- L4 migration 13 -> 14, export round trip, older-reader test (`extendedRules` absent from v1 keys).

## Status

Day-0 committed. Lanes dispatched.
