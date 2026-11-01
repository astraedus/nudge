# Rule snapshot and package facts — what the engine reads, and how often

Covers where block evaluation gets its rules from (`RuleSnapshotSource`), why a cold start reads the
database directly instead of trusting an empty snapshot, and the `PackageFacts` port that group and
"All apps" rules will read. The design is spec section 5.7 / 5.2
(`docs/exec-plans/active/groups-categories-allowlist.md`); this doc is the shipped shape.
**Read before touching `RuleSnapshot*`, `EvaluateBlockUseCase`'s rule reads, `AppGroupDao`'s Flow
queries, `PackageFacts` / `AndroidPackageFacts`, or `di/EngineModule.kt`.**

## Where things live

| Piece | File | Role |
|---|---|---|
| One value of every rule table | `data/repository/RuleSnapshot.kt` | `RuleSnapshot.of(rules, groups, members)`: the enabled rule rows, the groups, the rules mapped once to `BlockRuleData`, and the memberships of groups that exist. `BlockRule.toRuleData()` is the one entity-to-domain mapping. No `EMPTY` constant on purpose. |
| The port | `RuleSnapshots` (same file) | `current()` for app evaluation, `enabledRules()` for website evaluation. |
| Production source | `data/repository/RuleSnapshotSource.kt` (`@Singleton`, bound in `di/EngineModule.kt`) | `combine(getEnabledRules(), getAllGroups(), getAllGroupMembers())` → `stateIn(Eagerly, null)` on its own `CrashSafeScope` ("rule-snapshot", `Dispatchers.Default`). |
| Per-call reader | `RepositoryRuleSnapshots` (same file) | Reads the tables now: rules, groups, then one query per group. The cold-start path, and what a hand-built `EvaluateBlockUseCase` (every existing use-case test) uses. |
| Members query | `AppGroupDao.getAllMembers()` | One Flow over `app_group_members`, so a membership change re-emits without one query per group. |
| Package facts port | `domain/packages/PackageFacts.kt` | `factsFor(pkg)` → `PackageFact(category, launchable)`, plus `invalidate` / `invalidateAll`. `PackageFactsCache` is the pure per-package cache. |
| Package facts impl | `service/AndroidPackageFacts.kt` (bound in `di/EngineModule.kt`) | `PackageManager` reads on the IO dispatcher, cached per package, invalidated by a runtime `PACKAGE_ADDED/REPLACED/REMOVED/CHANGED` receiver. No consumer yet. |

## Why a snapshot

Before spec PR 1, every evaluation (each foreground change the accessibility service evaluates)
opened Room queries for all enabled rules, all groups and each group's members, and re-mapped every
rule. Once warm, `RuleSnapshotSource.current()` is a field read: a Room `Flow` re-runs its query only
when a write invalidates its table, so the tables are read once at start and once per change.
`EvaluateBlockSnapshotTest` (L3) counts opens of each table flow and pins zero once warm over 250
evaluations, app, group, unruled, website and the Following-steer check.

## Cold start: never "no rules"

Until the first combined emission, `snapshot.value` is `null` and `current()` falls back to
`RepositoryRuleSnapshots`, the exact read every evaluation did before the snapshot existed.

- **Rejected: an empty initial snapshot.** "No rules" is ALLOW for every app, and the first moments
  after the process starts are exactly when a blocked app is opened from a notification or a
  restored task. That is a fail-open.
- **Rejected: suspend until the first emission.** It ties every evaluation to the collector being
  alive and needs a timeout that must then fail one way or the other. The direct read needs neither,
  and is only paid until the first emission.

A read that throws on the cold path propagates to the caller, as before. The collector retries a
failed read with backoff (1 s doubling to 30 s) and, once warm, a later failure keeps serving the
last snapshot. The source is created lazily with the use case, so the first evaluation after process
start always takes the cold path; that is correct, merely not free.

## Freshness

A rule edit reaches the snapshot when Room's invalidation tracker re-runs the query, milliseconds
after the write commits. Rules are edited in Nudge's own screens and evaluated when another app
comes to the front, so the edit is in place by the next open, with no restart. Pinned in
`EvaluateBlockSnapshotTest` (an added rule and a removed membership both land on the next
evaluation without reopening any table), and on the bench in the spec PR 1 device QA.

## The service's one synchronous read (spec 5.5)

Every evaluation goes through the suspending `current()`. The one exception is
`NudgeAccessibilityService.isCoveredWholeNow`, which asks "does a group rule cover this browser as a
whole app right now?" from the event thread and the grant checks, where suspending or opening a
query is not allowed. It reads `RuleSnapshotSource.snapshot.value` directly (the source is exposed
on the service's Hilt entry point) and answers **false while it is null**. That is the one place a
cold snapshot fails open, and it is bounded: for the first moments after process start a grouped
browser gets website rules only, which is exactly what it got before spec 5.5. Anything that can
suspend must keep using `current()`, which never fails open. Details: `web-domain-blocking.md`,
"Browsers in a group".

## Group names

`RuleSnapshot.of` maps each group rule with the name of its group (`BlockRuleData.groupName`,
looked up once from the groups it already holds; null for a direct rule and for a rule whose group
row is missing). `RuleEvaluator.buildRuleName` uses it in place of the mode name, so the block
overlay shows "Rule: Social" rather than "Rule: Delay"; a group rule with no name shows "Group",
never a mode name. Qualifiers and the feature prefix stay ("Social (30 min/day)", "Reels - Social").
Pinned in `RuleEvaluatorGroupNameTest`.

## Package facts: failure direction

A package that cannot be read is `NOT_INSTALLED` (uncategorised, not launchable), so it is never
pulled into a category group or All-apps coverage. A not-installed answer is cached until an install
broadcast invalidates it; any other failure is not cached, so the next read retries.
`ApplicationInfo.CATEGORY_ACCESSIBILITY` (API 31) maps to `UNDEFINED` and is never offered as a group.

## Tests

| Layer | Test | Pins |
|---|---|---|
| L3 | `EvaluateBlockSnapshotTest` | zero table opens and zero per-group reads once warm; edits land on the next evaluation; cold start (members never emit) still blocks; a failing table is retried until warm; snapshot and per-call readers decide alike across modes, features, a bad mode string and a rule for a missing group |
| L1 | `EvaluateBlock*Test`, `FollowingSteerEnabledTest` | unchanged by spec PR 1: they run on the per-call reader, which is the proof the decisions did not move |
| L1 | `RuleEvaluatorGroupNameTest` | a group rule is named after its group in every mode, with qualifiers and feature prefix; "Group" for a missing name; direct rules unchanged; `RuleSnapshot.of` fills `groupName` |
| L1 | `PackageFactsCacheTest`, `AndroidPackageFactsCategoryTest` | cache hit/miss/invalidate, a throwing resolve is not cached; every `CATEGORY_*` mapping, unknown → `UNDEFINED` |
