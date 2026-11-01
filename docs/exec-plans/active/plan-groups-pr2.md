# Plan: Groups PR 2 (hand-picked groups you can reach and build)

Spec: `docs/exec-plans/active/groups-categories-allowlist.md`, sections 3.3, 3.6, 3.7, 5.5, 5.6, PR 2.
Base: main after PR 1 (#141, rule snapshot + essentials list).

## Lanes (disjoint files)

| Lane | Files | Owner |
|---|---|---|
| A. App picker | `ui/components/AppPicker.kt` | child (sonnet) |
| B. Group data + Strict | `domain/lock/GroupWeakening.kt`, `data/db/dao/AppGroupDao.kt`, `data/db/dao/BlockRuleDao.kt`, `data/repository/BlockRuleRepository.kt`, their tests, the L4 import test | child (sonnet) |
| C. Engine + service | `service/NudgeAccessibilityService.kt`, `service/PassthroughManager.kt`, `domain/engine/*`, `domain/model/BlockRuleData.kt`, `data/repository/RuleSnapshot.kt`, `domain/usecase/EvaluateBlockUseCase.kt`, their tests, `docs/architecture/web-domain-blocking.md` | child (opus) |
| D. Screens | `ui/screens/groups/*` (rewrite), `ui/screens/apps/*`, `ui/screens/config/*`, `ui/navigation/NavGraph.kt`, `ui/screens/rules/ActiveRulesViewModel.kt` (summary helper), CHANGELOG, `docs/architecture/rules-and-features.md`, `docs/architecture/strict-mode.md` | lane-pr2 |

## Decisions

- **One rule row per group**, always written on save; `enabled` = the "Block these apps" switch. Off = a
  named set (How/When collapse). Mode NONE is never written by the editor.
- **GroupWeakening** compares resolved membership (includes minus excludes). `GroupUsage.blocks` = the
  group has an enabled rule, any mode (a NONE rule with a daily limit still blocks after the limit;
  erring toward the challenge is the safe side); `allowedBy` = false until All apps rules exist (PR 4).
  Save gate = `GroupWeakening || RuleWeakening(oldRule, newRule)`. Delete gate = the same with an empty
  new definition and the rule turned off.
- **Browser coverage (5.5).** `isCoveredWholeNow(pkg)` = browser AND an enabled group rule whose group
  holds `pkg` is in its schedule now, read synchronously from `RuleSnapshotSource.snapshot` (cold =
  false, documented). A covered browser is evaluated as an app with **group rules only** (direct rules
  on a browser keep today's web-only meaning, F5). Block -> whole browser blocked; Allow -> website
  evaluation as today.
- **Passthrough** gains a whole-app axis: an app grant is whole; a website grant is whole only if the
  same sitting already held a whole grant. A covered browser lets through only a whole grant, so
  finishing a website's delay never opens a Hard-blocked browser.
- **Essentials** are hidden from the picker (spec 5.4). Engine enforcement of "essentials are never
  covered by a group" is PR 4/6's resolver.
- **Rule name** for a group rule is the group's name (`BlockRuleData.groupName`, filled by the snapshot).
- **Not in this PR:** category chips, "Allowed during", the "Block several apps" sheet, Manage Apps
  sections merge (PR 3), schedule text "Mon-Fri 9:00-17:00" (PR 3 shared summary format).

## Tests

- L1: `GroupWeakeningTest`, `buildRuleName` group, `BuildSummaryTextTest` group summary,
  `PassthroughManager` whole-axis, `WholeAppCoverage`.
- L2: `chrome-open-and-browse.jsonl` replay: covered Chrome routes to app evaluation, blocks, zero web
  evaluations; counterfactual pre-fix routing evaluates web and does not block.
- L3: group Delay + direct Hard on one app -> Hard.
- L4: import of a group used by an enabled rule only adds members (never weakens).
- Contract: the four service sites carry the coverage check; `NukeWiringContractTest` stays green.

## Status (2026-11-02)

Built, gated (scoped tests + every `*ContractTest`, `compileDebugKotlin`, `lintDebug` 0 errors), PR open
for the orchestrator to merge. Found and fixed after the first build:
- **Cold-start fail-open** (coordinator review): browser coverage read the in-memory snapshot, which is
  null before the first emission, so a grouped Chrome opened freely just after process start. Coverage
  now goes through `EvaluateBlockUseCase.isCoveredWhole`, which reads `RuleSnapshots.current()` (the
  direct-read fallback), and the service's synchronous check fails closed (`?: return true`).
- **Groups hidden on entry to Manage Apps** (device QA, n=4, from Home and after a save): LazyColumn
  key anchoring kept "Block several apps" as the first visible row, and the groups, arriving after the
  first frame, were inserted above it and so above the viewport. Everything above the apps is now one
  stable first row (`ManageAppsRow.Top`), the list waits for the first state with the groups, and a
  newly saved group scrolls back to the top (`newlyAddedGroupId`).
- **Unsaved edits dropped silently** when leaving the group editor: "Discard changes?" when the draft
  differs from what the editor opened with (`GroupEditorState.isDirty`).
- **"Hard Block" clipped off-centre** in the compact mode picker: one-word `blockModeShortLabel`.

Known, pre-existing, out of scope (coordinator: leave it, name it in the PR): the mid-session budget
trigger is fed by the counter cache, and `CounterCacheRefresher.appEntryFor` builds entries only from
rules that name a package. A GROUP rule's daily limit is therefore enforced when an app is opened
(the evaluation counts it), not at the minute it runs out while the app stays in front. Follow-up:
expand group rules to per-member cache entries.

## Device QA (device-tester, sonnet)

Social with 3 apps at Delay 15s; Strict remove -> challenge; Chrome in a Hard group blocked on any
site; "Also covered by Social"; delete group -> apps open. Screenshots to
`~/Pictures/screenshots/nudge/pr2/`.
