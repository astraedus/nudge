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

## Device QA (device-tester, sonnet)

Social with 3 apps at Delay 15s; Strict remove -> challenge; Chrome in a Hard group blocked on any
site; "Also covered by Social"; delete group -> apps open. Screenshots to
`~/Pictures/screenshots/nudge/pr2/`.
