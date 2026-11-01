# Plan: Groups PR 3 (one list: merge Active Rules into Manage Apps)

Spec: `docs/exec-plans/active/groups-categories-allowlist.md`, sections 3.1, 3.2, 3.7, 7 "PR 3", 8 item 4.
Base: main after PR 2 (#145, groups you can reach and build).

## Decisions

- **One summary format** lives in `ui/components/rule/RuleSummary.kt` (moved out of
  `ActiveRulesViewModel`'s companion). Per rule: mode, daily limit, schedule text, joined " · ".
  An app's rules read unscheduled app rule, then scheduled app rules, then feature rules.
  - Schedule text follows the engine's own semantics (`ScheduleEvaluator`): a time window needs both
    ends; no days, or all seven, means every day. Days compress into runs (`Mon–Fri`, `Sat, Sun`,
    `Mon, Wed`); times are 24h `9:00–17:00`. A rule that is active all the time adds nothing
    ("All the time" is omitted, spec 3.2). This replaces the old word "Scheduled".
  - A NONE rule's mode is left out when something else describes it ("30min limit", "Reels: Hard
    Block"), so a limit-only app no longer reads "Not blocked · 30min limit".
- **Sections are a pure builder** (`ManageAppsSections.kt`): installed apps + groups + members + rules +
  query -> groups, "Apps with rules", other apps, and the other apps' heading ("Apps" when the user has
  no group and no app rule at all, else "Other apps"). Search filters every section; groups match by
  name. "Block several apps" stays hidden while searching (PR 2). Seam for "All apps" (PR 4): one
  more field on the sections value, rendered first inside the Top row.
- **Key anchoring kept**: everything above the apps is still ONE `Top` row with key "top". Section
  headers are their own keyed rows (no dots, so they never collide with a package name).
- **Apps with rules** includes packages that have a direct rule but are not in the launcher list
  (uninstalled, or no launcher activity), resolved through the repository's cached name/icon lookup,
  so deleting Active Rules does not hide a rule the engine would still apply.
- **Switch semantics** (spec 3.2, today's Active Rules): an app row is on when ANY of its direct rules
  is on; off is Strict Mode gated ("Turn off blocking for this app"), on re-enables every direct rule,
  and a row with no rule creates Delay 15s. That row stays in place for the visit (`keepInPlace`), so the
  next app never slides under the finger (device QA finding).
- **Review round (orchestrator)**: a rule's schedule follows its own mode with a space (`Delay 30s ·
  Hard Block Sat, Sun`); an "other" app covered by a blocking group reads `In Social` /
  `In Social, Work` / `In 3 groups` via `blockingGroupsOf`, the resolver `coveringGroups` now uses. Group rows keep PR 2's gate. This also fixes the old
  `associateBy` that read only the last rule of a package.
- **Home tile** "Active Apps" opens Manage Apps. Its count = packages the engine resolves at least one
  enabled rule for: the union of direct rule packages and enabled group members, deduped, built with
  `RuleSnapshot.of` + `RuleEvaluator.resolveRulesForPackage` (`domain/engine/CoveredApps.kt`). The
  engine does not exclude essentials from groups yet (PR 4/6), so neither does the count.
- **Deleted**: `ActiveRulesScreen`, `ActiveRulesViewModel`, the `active_rules` route. Backup is reached
  from Settings only.

## Tests

- L1: `BuildSummaryTextTest` (moved to `ui/components/rule/`, schedule cases updated from "Scheduled" to
  the schedule text), `ScheduleTextTest`, `ManageAppsSectionsTest` (empty, typical, heavy, search by
  group name, search by an app with rules, uninstalled rule package, switch state), `ManageAppsRowsTest`,
  `CoveredAppsTest` (direct + group overlap deduped, disabled rule and disabled group rule not
  counted).
- L2: `AppListViewModelStrictModeTest` (gate from app rows and group rows), `HomeBlockedTileTest`
  (tile count wiring).
- Contracts: `BackupEntryPointsContractTest` (Settings is the single entry point),
  `HoldModeContractTest` labeller path.

## Device QA

Pixel 3 via `device-tester`: fresh state, typical (2 app rules + 1 group), heavy (3 groups + 8 app
rules, some off), Strict Mode turn-off from a group row and an app row, Home tile count.

Findings fixed after the first run: searching a group name showed the group AND "No apps or groups
match" (now `nothingMatches`); switching an app on moved it up mid-tap, so a fast second tap switched
the next app on (now kept in place; `SwitchedOnStaysInPlaceTest` fails without the fix).
