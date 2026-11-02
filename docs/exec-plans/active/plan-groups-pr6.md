# Plan: Groups PR 6, category groups

Spec: groups spec 3.3 (editor), 5.2 (membership), 5.5 (browsers), 5.6 (Strict Mode).

## Decisions

- **No KnownAppCategories table.** Bench Pixel 3, 48 launchable apps: SOCIAL 11, PRODUCTIVITY 17,
  UNDEFINED 14, IMAGE 2, VIDEO 2, AUDIO 1, MAPS 1. Every big-name Play app reports a real
  `ApplicationInfo.category`; the UNDEFINED ones are sideloaded, dev or system apps. A hand list
  would be a second source of truth that rots. Owner-visible quirk: Chrome, Gmail and Contacts
  report SOCIAL; LinkedIn and Firefox report PRODUCTIVITY. "Removed by you" is the escape hatch.
- **Membership is resolved, never stored** (spec 5.2):
  `member = !essential && !excluded && (included || category in categories)`. UNDEFINED never
  matches. One formula: `domain/groups/GroupMembershipResolver`.
- **Rows:** `app_group_members.excluded` (0 = "Add apps", 1 = "Removed by you"), PK keeps one row
  per app. `app_groups.categories` is a CSV of offered category names, NULL for none
  (`AppCategoryCsv`). DB 14 -> 15, two `ADD COLUMN`s.
- **Hot path:** the snapshot carries the resolver. The category is read only when an enabled rule
  targets a category group (`RuleCoverage.categoryNeeded`), from the cached `PackageFacts`; the
  synchronous browser path reads `cachedOrNull` and fails closed on a miss.
- **Strict Mode:** checked at Save over the RESOLVED members on this device: unchecking a category
  that brings in apps is weakening, one that brings in none is free, "Removed by you" is a removal.
- **Export:** envelope stays v1; groups gain `categories` / `includes` / `excluded`, and `members`
  stays the resolved snapshot so an older Nudge imports a usable group.
- **Package changes:** the one runtime receiver in `AndroidPackageFacts` also refreshes
  `InstalledAppsRepository`; no second receiver.

## Lanes

- A (me): migration, resolver, engine + every consumer, editor model + UI, Strict, docs, gate, QA.
- B: export/import + import weakening + tests + export-import.md.
- C: installed-apps refresh from the package receiver + tests + debug-receiver-not-in-main contract.
- D: existing tests migrated to the resolver signatures.

## Known gap

The block screen's "allowed" list for an All apps rule names hand-added apps only, not category
members (display only; enforcement is resolved).
