# Rule snapshot and package facts — what the engine reads, and how often

Covers where block evaluation gets its rules from (`RuleSnapshotSource`), why a cold start reads the
database directly instead of trusting an empty snapshot, and the two device facts (essential,
launchable) that group and "All apps" coverage read. The design is spec section 5.7 / 5.2
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
| Package facts impl | `service/AndroidPackageFacts.kt` (bound in `di/EngineModule.kt`) | `PackageManager` reads on the IO dispatcher, cached per package, invalidated by a runtime `PACKAGE_ADDED/REPLACED/REMOVED/CHANGED` receiver (the ONE receiver; never a second, never manifest). The same receiver also calls `InstalledAppsRepository.refresh()`, which clears its cache and bumps its `changes` counter, so Home's "N apps blocked" and Manage Apps re-read the list and a newly installed app joins its category group in the counts. `NudgeApp` field-injects `PackageFacts`, so the receiver is registered for the whole process lifetime, not only once the accessibility hot path first asks for a fact. Read by `EvaluateBlockUseCase` for All apps coverage. |
| Essentials port | `domain/packages/EssentialPackages.kt`, impl `service/EssentialApps.kt` | `current()` (cached 5 minutes) and `cachedOrNull()`. The one essentials list, read by Nuke and by group and All apps coverage. |

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

Every evaluation goes through the suspending `current()`, including the browser coverage question
(`EvaluateBlockUseCase.isCoveredWhole`, which decides whether a browser is evaluated as an app), so
a cold start is fail closed for a grouped browser exactly as for any app. The one synchronous read is
`NudgeAccessibilityService.isCoveredWholeNow`, used only where suspending or opening a query is not
allowed: the grant checks and the feature-detection guard on the event thread. It reads
`RuleSnapshotSource.snapshot.value` directly (the source is exposed on the service's Hilt entry
point) and answers **true for a browser while it is null**, so the grant check accepts only a
whole-app grant until the snapshot is warm. Nothing reads `snapshot.value` and fails open. Anything
that can suspend must keep using `current()`. Details: `web-domain-blocking.md`, "Browsers in a
group".

## Device facts on the hot path (spec 4.1)

Group and All apps coverage need two facts the rule tables do not hold: is the package essential,
and does it have a launcher entry. `EvaluateBlockUseCase` asks `RuleCoverage.factsNeeded` first and
reads only what a rule can consult: nothing for a package with only its own rules, the essentials
list for a member of a group with a rule, both under an All apps rule. Both reads are in-memory once
warm (the essentials list for 5 minutes, launchable per package until an install broadcast), so an
All apps rule adds no database or binder call per event. A newly installed app is not in the cache
yet, so its first evaluation reads it fresh: an All apps rule covers it on its first open.

The service's synchronous check (above) cannot suspend, so it reads `EssentialApps.cachedOrNull()`
(empty if never resolved) and treats a browser as launchable. Both mistakes only send the browser to
the suspending evaluation, which reads the real facts.

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
| L3 | `EvaluateBlockAllAppsTest` | with an All apps rule: zero table opens and zero per-group reads once warm (including `isCoveredWhole`); no device-fact read for a package with only its own rule; one read of each fact per All apps evaluation; a newly installed app covered on its first evaluation |
| L1 | `RuleCoverageTest` | `factsNeeded` asks for nothing with app-only rules, and a fact it skips never changes any rule's coverage |
