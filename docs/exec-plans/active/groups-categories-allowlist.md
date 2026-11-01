# Design spec: groups, category groups, "all apps except", and first-unlock schedules

Status: **approved 2026-11-02, building.** The owner's calls are in section 8. The main one: groups
*can* be listed as allowed on an All apps rule in v1 (the former follow-up F1). It follows the direction in the owner's comment on #107: no
third block system next to rules and Nuke. Instead, rules and groups get three pieces that compose:

1. **Groups you build, plus category groups.** A group can be filled from Android's app category
   (Social, Video, Games ...), with your own add/remove overrides. New installs in that category join
   on their own.
2. **"All apps except these."** A rule can cover every app except a few. A fixed set of essentials
   (phone, messages, launcher, keyboard, Settings, ...) is always usable and can't be removed.
3. **A "first unlock + N minutes" schedule**, next to the existing days-and-times schedule. The new day
   starts at a set hour (default 4:00 AM).

Morning Lock is then one rule: *all apps except [Spotify, Maps], hard block, 30 minutes after first
unlock*.

Credit: a large part of the engine design below comes from @danieltsam's Morning Lock design in #109
(closed in favour of this direction). Every place that reuses a #109 finding says so, and section 9
collects them all.

---

## 1. Mental model

### The nouns the user holds

| Noun | What it is | New? |
|---|---|---|
| **App** | One installed app. Has block settings. | Today |
| **Group** | A named set of apps (picked by hand, filled from a category, or both). Has block settings. | Exists in code, unreachable today |
| **All apps** | Every app on the phone except the ones you list. Has block settings. | New |
| **Nuke** | The no-way-around-it list. Unchanged, still separate. | Today |

That's three targets that all work the same way, plus Nuke. Everything else is an option *inside*
one of them, not a new thing to learn:

- A **category** isn't a noun. It is one way to fill a group ("Add from category").
- **"Except"** isn't a mode the user switches on. It is what "All apps" means.
- **First unlock** isn't a noun. It is a third choice under "When", next to "All the time" and
  "Days and times".
- The **essentials** aren't something the user manages. They show up as one locked row ("Always
  allowed") that opens a read-only list.

The word "rule" stays in the code, and in the few places it already shows ("Remove all rules"). The
user thinks in targets: *this app*, *this group*, *all apps except these*. Each has the same three
questions, asked in the same order on every editor: **which apps, how, when.**

### Where "everything except" lives: on the rule (decided)

The lead leaned towards the rule. This spec agrees, with one refinement: the polarity is expressed by
**choosing the target "All apps"**, not by a Block/Allow toggle on some rule. The allowed apps belong
to that rule. They are not a shared group. Reasons:

1. **A group is just a set, and a set has no direction.** "Work apps" can be blocked on weekends and
   be what you're allowed during a work morning. If polarity lived on the group, you'd need two groups
   with the same members, and they would drift apart.
2. **One place answers "what does this block?"** For an all-apps rule, the answer is on the rule's
   own screen ("Except these apps"). You never have to open a group to learn whether it means
   "blocked" or "allowed".
3. **The direction lives on the reference, not the group.** An All apps rule's allowed list holds
   apps *and* groups ("all apps except Spotify and my Work group"; owner's call, 2026-11-02). The
   group itself still has no direction: "Work" can be blocked by its own rule on weekends and be
   listed as allowed on Morning Lock, with one set of members. The cost is in Strict Mode, where a
   group edit is now checked against every way the group is used. Removing an app weakens a group
   that blocks, and adding an app weakens a group that allows. That check is one pure function
   (`GroupWeakening`, section 5.6), not something each screen works out.
4. **No toggle to misread.** "Block everything except Social" is a sentence nobody means. With the
   target choice there's nothing to flip, so it can't be built by accident.

**An allowed group is live.** It references the group, not a copy of its members. An app that joins
the group later (picked by hand, or a new install in one of its categories) is allowed from then on,
and "Blocks 47 apps" updates. The editor says this in one line under the group's row (section 3.4).

### Precedence in one sentence

> **Every rule that covers an app applies, and the strictest one wins. Nuke beats every rule. Your
> essentials are never covered by a group or an All apps rule.**

"Except" means *this rule doesn't cover these apps*. It does **not** mean "these apps are always
allowed". An app on Morning Lock's allowed list still gets its own rules: if YouTube has its own
Shorts block, Morning Lock leaving YouTube open doesn't lift that. So rules only ever *add*
restrictions, and adding a rule can never weaken anything. That is what lets Strict Mode treat "add
a rule" as always free. #109's design had an explicit "allowed wins" short-circuit (its Q3). This
spec doesn't adopt it: the short-circuit is the one shape in which adding a rule could unblock an
app.

---

## 2. Today's information architecture (walked in code)

There is no bottom nav. Home is a scrolling dashboard. Routes are in `ui/navigation/NavGraph.kt`.

```
Home
 ├─ "Active Apps" tile ──────────► Active Rules ──► App config (UnifiedAppConfigScreen)
 ├─ NukeCard ────────────────────► Nuke (its own app checklist)
 ├─ "Manage Apps" quick action ──► Manage Apps (AppListScreen) ──► App config
 ├─ Stats tiles/cards ───────────► Stats / Willpower / Interventions / App detail
 └─ "Settings" quick action ─────► Settings (… Backup …)

Unreachable: Groups (GroupScreen)     — declared in NavGraph, nothing navigates to it
Dead:        Rule editor (RuleEditorScreen) — only navigates to itself
```

What this design fixes or makes redundant:

| Today | Problem | In this design |
|---|---|---|
| `GroupScreen` | No entry point. Member count hardcoded to 0 (`GroupViewModel`: `GroupWithCount(group, 0)`). No rename, delete, or block settings. Adds one app per dialog. | Replaced by the group editor (section 3.3), reached from Manage Apps. |
| `RuleEditorScreen` + VM | Dead duplicate of App config. Its private `InfoButton`/`TimeSelector` are copies of App config's. | Deleted in PR 0. The shared pieces move to `ui/components/rule/`. |
| Group rules | The engine honours `BlockRule.groupId`, but no UI creates one. `AppListViewModel` and `ActiveRulesViewModel` both filter `packageName != null`, so imported group rules are invisible. | Shown as rows in Manage Apps, and on App config as "Also covered by". |
| Manage Apps and Active Rules | Two lists for one job. Both open App config. The switch means different things (create-at-Delay-15s vs. toggle all). | Merged into one Manage Apps (PR 3). The Home tile points there. |
| Manage Apps switch | Turns a rule off without the Strict Mode challenge (Active Rules does challenge). | Being fixed separately, before this work. The merged list (PR 3) keeps the challenge. |
| Nuke copy | "Your launcher, phone, keyboard and Settings are never on this list." | Same sentence, from the one shared essentials list (section 5.4). |
| Backup in two places | Settings and the Active Rules overflow. | Settings only, once Active Rules is merged. |

---

## 3. Information architecture and screens

### 3.1 What sits where (progressive disclosure)

| Level | What | Where |
|---|---|---|
| **Primary** (first run, unchanged) | Pick an app, set a delay | Manage Apps → flip a switch (Delay 15s), or tap the app → pick a delay → Save. Same taps as today. |
| **Secondary** | Block a group of apps; block all apps except a few | One row on Manage Apps: **"+ Block several apps"**. Once these rules exist they show as rows above the app list. |
| **Secondary** | See what is locked right now | A Home card, shown **only while** an All apps rule is active. |
| **Tertiary** | Fill a group from a category, remove apps by hand, new-install behaviour | Inside the group editor |
| **Tertiary** | First unlock + N minutes | Third choice under "When" in every editor |
| **Tertiary** | New day start hour | Inside the "After first unlock" choice |
| **Tertiary** | Which apps are essential | One locked row in the All apps editor; tap for the list |
| **Tertiary** | Why an app is blocked by something else | "Also covered by" on that app's config, only when it applies |

A brand-new user sees exactly one new row on Manage Apps. Nothing else changes until they tap it.

### 3.2 Manage Apps (merged list; replaces Active Rules)

Sections show only when they have something in them. Search filters every section, matching group
names as well as app names.

```
┌──────────────────────────────────────────────┐
│ ←  Manage Apps                               │
├──────────────────────────────────────────────┤
│ ⌕  Search apps                               │
│                                              │
│ All apps                                     │
│ ▦  All apps except Spotify, Maps      [ ●]   │
│    Hard block · 30 min after first unlock    │
│                                              │
│ Groups                                       │
│ ◫  Social · 9 apps                    [ ●]   │
│    Delay 15s                                 │
│ ◫  Games · 12 apps                    [  ]   │
│    Hard block · Mon–Fri 9:00–17:00           │
│                                              │
│ ＋ Block several apps                        │
│                                              │
│ Apps with rules                              │
│ [ic] Instagram                        [ ●]   │
│      Delay 15s · Reels: hard block           │
│ [ic] YouTube                          [ ●]   │
│      30 min a day                            │
│                                              │
│ Other apps                                   │
│ [ic] Calculator                       [  ]   │
│ [ic] Camera                           [  ]   │
│ …                                            │
└──────────────────────────────────────────────┘
```

- **First run:** no "All apps", "Groups" or "Apps with rules" sections. The screen is the search
  field, the one "+ Block several apps" row, then a section headed **"Apps"** (not "Other apps").
- **Summary lines** use one format everywhere. It is today's `ActiveRulesViewModel.buildSummaryText`
  style ("Delay 15s · 30min limit"), extended with the target and schedule:
  - `All apps except Spotify, Maps` (the first two allowed names, then `+N`)
  - `Social · 9 apps`
  - schedules: `All the time` (omitted), `Mon–Fri 9:00–17:00`, `30 min after first unlock`
- **Switches** follow today's Active Rules rules. Turning one off is Strict Mode gated. On an app row
  with no rule, the switch creates Delay 15s, as today.
- **Icons:** stock Material `Apps`/`GridView` for All apps and `Folder` for groups, in a 40dp
  container that matches `AppListItem`'s icon size. No custom art.
- **The Home tile** "Active Apps" opens this screen. Its count becomes "apps covered by a rule that
  is on" (today it counts rule rows, under a label that says apps).

**"+ Block several apps"** opens a standard modal bottom sheet. This is the one place a short
description earns its place, because the choice is the whole point of the sheet:

```
┌──────────────────────────────────────────────┐
│ Block several apps                           │
│                                              │
│ ◫  A group of apps                        ›  │
│    Pick apps, or a category like Social      │
│                                              │
│ ▦  All apps except a few                  ›  │
│    Only the apps you choose stay open        │
└──────────────────────────────────────────────┘
```

### 3.3 Group editor (new; replaces GroupScreen)

One screen: the group's apps first, then how and when it is blocked. It is laid out like App config,
so it feels familiar: top bar with **Save**, then sections. A group is usually made to be blocked, so
the group and its own rule are edited together on this one screen. In v1 one group has at most one
rule of its own (follow-up F2).

```
┌──────────────────────────────────────────────┐
│ ←  New group                          Save   │
├──────────────────────────────────────────────┤
│ Name   [ Social                          ]   │
│                                              │
│ Apps · 9                                     │
│ Add from category                            │
│ [✓ Social & communication 7] [Movies & vi…3] │
│ [Games 12] [News & magazines 2] [Music & a…4]│
│ [Photos & images 5] [Maps & navigation 2]    │
│ [Productivity 9]                             │
│ New apps in these categories join            │
│ automatically.                               │
│                                              │
│ [ic] Instagram       Social & comm…     ✕    │
│ [ic] TikTok          Social & comm…     ✕    │
│ [ic] Reddit                             ✕    │
│ …                                            │
│ ＋ Add apps                                  │
│ Removed by you · 2                       ›   │
│                                              │
│ Block these apps                      [ ●]   │
│ How                                          │
│ [Hard block | Delay | Hold | Breathing]      │
│ Wait  [5s] [15s] [30s] [60s] [Custom]        │
│ Daily limit for each app              [  ]   │
│                                              │
│ When                                         │
│ ◉ All the time                               │
│ ○ Days and times                             │
│ ○ After first unlock                         │
│                                              │
│ Allowed during                               │
│ ▦ Morning Lock                           ›   │
│                                              │
│ [ Delete group ]                             │
└──────────────────────────────────────────────┘
```

- **The order is the user's question order: which apps, then what happens to them.** The group's own
  block is one switch, **"Block these apps"**, which heads the How and When sections. A group can
  exist only to be allowed somewhere (a "Work" group listed on Morning Lock). With the switch off, How
  and When collapse out of sight and the group is just a named set. Turning it on reveals them with
  their last values. This is the same rule as the old "Enabled" switch (its `enabled` flag), so
  Strict Mode gates turning it off, as today.
- **"Allowed during"** lists the All apps rules that list this group as allowed. It shows only when
  there is at least one, and each row opens that rule. This is the one place a group's two uses meet,
  so the user can see that adding an app here lets it through Morning Lock. Under Strict Mode that
  addition is what triggers the challenge (5.6).
- **Category chips** are `FilterChip`s, labelled with the system's own localized category title
  (`ApplicationInfo.getCategoryTitle`) plus the number of installed apps in that category. The count
  is the cue for coverage: a chip reading "News & magazines 0" tells the user Android doesn't know
  about their news apps, without any extra text. Offered: GAME, AUDIO, VIDEO, IMAGE, SOCIAL, NEWS,
  MAPS, PRODUCTIVITY. **Not offered:** ACCESSIBILITY (API 31+), because blocking assistive apps by
  category is never wanted. **Not shown:** UNDEFINED.
- **"New apps in these categories join automatically."** is shown only when at least one category is
  checked. It is the only explanatory line on the screen, because it describes something the user
  can't see happen.
- **Member rows:**
  - Rows that came from a category show that category's name as quiet trailing text
    (`labelSmall`, `onSurfaceVariant`). Hand-added rows show nothing.
  - ✕ on a hand-added app removes it.
  - ✕ on a category app moves it to **"Removed by you"**, a row that opens a list where each app has
    **Add back**.
  - Essentials never appear here (section 5.4).
- **"＋ Add apps"** opens the shared multi-select app picker (3.6). Picking an app that was in
  "Removed by you" restores it.
- **"Daily limit for each app"** is labelled "each app" on purpose. `dailyUsageMs` is per package, so
  a group rule's limit is per app, not one shared budget. A shared group budget is follow-up F3. The
  wording keeps today's behaviour honest instead of implying a pool.
- **Not on this screen:** in-app feature rules, web domains, Tab Vanish and Following steer. Those
  are per-app (they depend on one app's screens) and stay on App config.
- **When:**
  - "All the time" and "Days and times" behave exactly as App config's scheduled override does (same
    day chips, same Start/End `TimeSelector`).
  - "After first unlock" is described in 3.5.
  - The choices use a radio group (`selectableGroup` + `RadioButton` rows), the standard pattern for
    one choice out of three that each reveal fields.
- **Delete group** deletes the group, its members and its rules in one transaction. It shows a
  confirm dialog. Under Strict Mode it is gated (section 5.6).
- **Save:**
  - It is disabled while the name is blank.
  - It is disabled while the group has no apps.
  - The top bar's title shows the group name once saved.

### 3.4 All apps editor (new)

```
┌──────────────────────────────────────────────┐
│ ←  All apps except a few              Save   │
├──────────────────────────────────────────────┤
│ Enabled                               [ ●]   │
│ Blocks 47 apps                               │
│                                              │
│ Except these                                 │
│ ◫ Work · 6 apps                     ›   ✕    │
│ [ic] Spotify                            ✕    │
│ [ic] Maps                               ✕    │
│ [ic] Instagram   Also blocked by Social ✕    │
│ ＋ Add apps or groups                        │
│ 🔒 Always allowed: Phone, Messages,      ›   │
│    Settings and 5 more                       │
│                                              │
│ How                                          │
│ [Hard block | Delay | Hold | Breathing]      │
│                                              │
│ When                                         │
│ ○ All the time                               │
│ ○ Days and times                             │
│ ◉ After first unlock                         │
│   For   [15 min] [30 min] [1 h] [Custom]     │
│   Days  [M][T][W][T][F][S][S]                │
│   New day starts at 4:00 AM              ›   │
│   Starts at your next first unlock.          │
│                                              │
│ [ Delete rule ]                              │
└──────────────────────────────────────────────┘
```

- **"Blocks 47 apps"** is the one number that tells the user how big this is. It counts the
  launchable apps the rule would cover: all launchable apps, minus the essentials, minus the
  allowed apps, minus the current members of the allowed groups. It updates live as apps or groups
  are added. It is the main guard against "I didn't realize that meant my bank app".
- **Allowed groups come first** in "Except these", as one row each: the group icon, the name and its
  live member count. Tapping the row opens the group editor, and ✕ takes the group off this list
  without touching the group. The member apps aren't spelled out here, because the count plus one tap
  is enough and listing them would bury the single apps. If any member is still blocked by another
  rule, the row's secondary line says so: "2 still blocked by other rules".
- **"Add apps or groups"** opens the shared picker (3.6) with a **Groups** section above **Apps**.
- **"Also blocked by Social"** shows on an allowed app only when another rule that is on would still
  block it. This is precedence made visible at the point of confusion: the user has just said "keep
  Instagram open", and this tells them in one line why it won't be.
- **The locked essentials row** uses a lock icon with no ✕ or checkbox. Tapping it opens a read-only
  sheet:

  ```
  ┌──────────────────────────────────────────────┐
  │ Always allowed                               │
  │ These apps are never blocked by a group      │
  │ or an All apps rule.                         │
  │                                              │
  │ [ic] Phone        [ic] Messages              │
  │ [ic] Pixel Launcher [ic] Gboard              │
  │ [ic] Settings     [ic] Clock                 │
  │ [ic] Personal Safety [ic] Nudge              │
  │                                  [ Close ]   │
  └──────────────────────────────────────────────┘
  ```

  The list is resolved on the device (default dialer, SMS role, launcher, keyboards, and so on), so
  it shows *this* phone's apps, not generic names.
- **How:** mode only. The wait time chips show for Delay/Hold. Auto-kick, reminders, counter,
  grayscale, daily limit, web domains and feature rules are all hidden: they are per-app tools, and
  on a rule covering 47 apps they read as noise. Keep-pass-on-short-trips is also hidden, and forced
  off for these rules.
- **"Starts at your next first unlock."** shows only when this save will (re)arm a first-unlock rule
  (section 5.3, arming). It answers "why isn't it locking now?" before it's asked.
- **Saving "All the time" with Hard block** shows a standard confirm dialog first: *"Block 47 apps all
  the time? Only the apps you listed and your essentials will open."* with **Block them** and
  **Cancel**. No other combination is confirmed.
- Several All apps rules can exist (for example a morning one and a bedtime one). Each is its own row
  on Manage Apps.

### 3.5 The "When" section (shared by group, All apps and App config)

```
When
○ All the time                       (not on App config's scheduled override)
○ Days and times
    Days   [M][T][W][T][F][S][S]
    Start  [06:00]   End  [09:00]
◉ After first unlock
    For    [15 min] [30 min] [1 h] [Custom]
    Days   [M][T][W][T][F][S][S]
    New day starts at 4:00 AM    ›
```

- **App config** keeps its "Scheduled Override" section and its "Enable schedule" switch. Inside it,
  a two-segment `SingleChoiceSegmentedButtonRow` **[Days and times | After first unlock]** picks the
  kind. So a single app can do "Instagram, hard block, 60 minutes after first unlock".
- **"New day starts at 4:00 AM"** is one setting for the whole app, because there is only one "first
  unlock of the day". It shows wherever "After first unlock" is chosen, always with the same value,
  and tapping it opens the existing `TimeSelector` in a dialog. There is no separate Settings row:
  only first-unlock rules use it, so it lives where they are made.
- **Days** for a first-unlock rule are the days of the *logical* day. With the default 4:00 start, a
  2:00 AM Saturday unlock belongs to Friday.
- **"Custom"** reuses `CustomTimeDialog`, in minutes, from 5 to 240.

### 3.6 Shared app picker (new component)

This is a full-screen multi-select, used by "Add apps" in both editors (and later, optionally, by
Nuke). It is standard: a search field, rows with checkboxes, and a top bar with the selected count
and **Done**. It replaces today's add-one-app-and-close dialog. Essentials aren't listed. The All
apps editor's picker also hides nothing else, because the user may allow any app.

In the All apps editor only, the picker opens with a **Groups** section (each group's name and member
count, with a checkbox) above the **Apps** section. Search filters both. When there are no groups, the
section isn't shown, so there's no empty state to explain. The group editor's picker has no Groups
section: a group holds apps, never other groups.

```
┌──────────────────────────────────────────────┐
│ ✕  3 selected                         Done   │
├──────────────────────────────────────────────┤
│ ⌕  Search apps                               │
│ [ic] Calendar                          [✓]   │
│ [ic] Camera                            [ ]   │
│ [ic] Maps                              [✓]   │
│ …                                            │
└──────────────────────────────────────────────┘
```

### 3.7 App config: "Also covered by"

At the top of App config, under the Enabled switch, and **only when it applies**:

```
Also covered by
◫ Social · Delay 15s                          ›
▦ All apps except Spotify · 30 min after unlock ›
```

Each row opens that group or rule. This is how "why is this app blocked when its own rule is off?"
gets answered without a help page. It is read-only here: the app's own switch still means *its own*
rules only.

### 3.8 Home: the lock status card

The card shows **only while** an All apps rule is active right now. It sits under the NukeCard and
uses the same `surfaceVariant` card:

```
┌──────────────────────────────────────────────┐
│ All apps except Spotify, Maps are blocked    │
│ Until 8:42 AM                             ›  │
└──────────────────────────────────────────────┘
```

- **Tap:** opens the rule.
- **More than one rule active:** the card shows the one that ends last.
- **Nothing active:** nothing is shown. No empty state, no "set up Morning Lock" upsell.

### 3.9 Block screen for an All apps block

The overlay content (`HardBlockContent`, `DelayContent`, `HoldContent`, `BreathingContent`) already
shows `Rule: $ruleName`. For an All apps rule:

```
            (title from the message pool)
            (subtitle from the message pool)

        Rule: All apps except Spotify, Maps
        Until 8:42 AM

        Open an allowed app
        [Spotify]  [Maps]

              [ Leave ]
```

- **`ruleName`** comes from `RuleEvaluator.buildRuleName`, extended for these targets: "All apps
  except Spotify, Maps" or "Social". The mode stays in the overlay itself, as today.
- **"Until 8:42 AM"** shows for first-unlock and days-and-times rules. It isn't shown for "All the
  time". It is new for every scheduled rule, not only All apps, and it costs one line.
- **"Open an allowed app"** shows up to four allowed-app icons. Each launches its app through the
  launch intent. This only appears for All apps blocks. It turns a dead end into the next useful
  step, so the user doesn't press Home and hunt. The redirect bubble stays hidden for these blocks
  (as #109 proposed), because these icons replace it.
- **Stats:** these blocks count as blocks in stats like any other, following their mode (#109: hard
  blocks are counted as hard blocks).

---

## 4. Precedence and conflicts

### 4.1 Which rules cover an app

For a foreground package `p`, the candidate rules are:

1. **App rules:** the rules where `packageName == p` (today).
2. **Group rules:** the rules where `groupId` is a group that `p` belongs to (today), using the new
   membership (5.2).
3. **All apps rules:** the rules where `allApps` is true, `p` is launchable, `p` is not in
   `exceptPackages`, `p` is not a member (resolved, 5.2) of any group in `exceptGroupIds`, and `p` is
   not essential.

In cases 2 and 3, `p` must not be essential. Then the schedule filter applies, including the new
first-unlock window. Then `BlockEngine.evaluate`'s existing strictest-wins runs unchanged:

```
NUKE > unconditional HARD_BLOCK > daily limit spent (HARD_BLOCK) > DELAY > HOLD > BREATHING > allow
```

The engine itself needs no new priority tier. "Except" and "first unlock" only change *which rules
reach it*.

### 4.2 The matrix

| Situation | Result | Why |
|---|---|---|
| App rule Delay 15s, group rule Hard block | Hard block | Strictest wins |
| Allowed by Morning Lock, but in group "Social" (Hard block) | Hard block | "Except" means "not covered by this rule", not "always allowed". The editor shows "Also blocked by Social". |
| Allowed by Morning Lock, has its own Shorts feature rule | The Shorts rule applies | Same reason |
| App rule turned off, group rule on | The group rule applies | Each rule's switch is its own. App config shows "Also covered by". |
| Two All apps rules active, different allowed lists | An app is blocked if *either* covers it | Strictest wins. The allowed lists don't add up. |
| Group "Work" blocked by its own rule *and* allowed on Morning Lock | Work's own rule applies | Morning Lock doesn't cover Work's apps, and Work's rule still does. Allowing never lifts another rule. |
| Nuke on, app on the Nuke list | Nuke, always | Nuke runs first in `EvaluateBlockUseCase` and skips every grant (today) |
| Nuke on, app allowed by Morning Lock but on the Nuke list | Nuke | Same |
| Essential app (Phone) under an All apps or group rule | Allowed | Never covered (5.4) |
| Essential app with its own app rule the user made | That app rule applies | An explicit per-app choice is honoured, as today. Only *group* and *All apps* coverage skips essentials. |
| Strict Mode | Doesn't change which rules apply. It only gates *edits* that weaken them (5.6). | |

### 4.3 Passes and grants

All of today's grants are per package, and stay that way. They don't care which rule decided.

- **Daily 2-minute emergency pass:** works on an All apps block like on any block, if the user turned
  it on in Settings. It is still the escape hatch for the "I locked myself out of my bank app"
  moment. It doesn't apply under Nuke (today).
- **Completed delay (passthrough):** per package. An All apps rule in Delay mode gives each app its
  own delay, the same as a group rule.
- **Keep the pass on short trips:** read off the *deciding* rule (today). It is hidden and forced off
  on All apps rules (3.4). It is offered on group rules, and is Strict Mode gated when turned on, as
  today.
- **Pass earned before a stricter rule began:** existing behaviour, unchanged. A pass earned under a
  Delay rule lasts for that sitting, even if a stricter scheduled rule starts mid-sitting. It doesn't
  matter for first-unlock rules: the window opens at unlock, which always starts a new sitting (the
  screen was off). Follow-up F4 covers revisiting it for days-and-times windows.

### 4.4 Explaining it

Everything above fits the one sentence in section 1. Where the user meets a conflict, the UI states
the specific case in place: "Also blocked by Social" (All apps editor) and "Also covered by" (App
config). There is no precedence help page.

---

## 5. Engine and data

### 5.1 Data model and migrations

Each PR that changes the schema brings its own migration and bumps `NUDGE_DB_VERSION` by one. If the
PRs land in a different order, the numbers are reassigned at merge. Every migration is additive
(`ALTER TABLE … ADD COLUMN` with defaults), so existing rows keep today's meaning.

| PR | Table | Column | Type / default | Meaning |
|---|---|---|---|---|
| 6 (categories) | `app_groups` | `categories` | `TEXT NULL` | CSV of category *names* (`SOCIAL,VIDEO`). NULL = hand-picked only. |
| 6 | `app_group_members` | `excluded` | `INTEGER NOT NULL DEFAULT 0` | 1 = "removed by you" (a category app taken out by hand) |
| 4 (all apps) | `block_rules` | `allApps` | `INTEGER NOT NULL DEFAULT 0` | Target is all apps |
| 4 | `block_rules` | `exceptPackages` | `TEXT NULL` | CSV of allowed packages, like `webDomains` and `inAppFeatures` |
| 4 | `block_rules` | `exceptGroupIds` | `TEXT NULL` | CSV of allowed group ids. Live references: membership is resolved at evaluation (5.2). |
| 5b (first unlock) | `block_rules` | `firstUnlockMinutes` | `INTEGER NULL` | Non-null = active for N minutes after the first unlock of the day |
| 5b | `block_rules` | `armedAt` | `INTEGER NOT NULL DEFAULT 0` | Wall-clock ms the rule was last saved or switched on (see arming) |

Preferences (`NudgePreferences`, DataStore):

- `day_start_minute`: Int, default 240.
- `first_unlock_at`: Long, the wall-clock ms of the first unlock of the current logical day.

**Target invariant:** exactly one of `packageName`, `groupId` and `allApps` is set. The repository
enforces it on write, and a pure `RuleTarget.of(rule)` sealed type is what the rest of the code
reads. That way no caller re-derives "which kind is this" from nulls.

**Why a CSV on the rule, and not a hidden group, for the allowed list:** it is the codebase's
existing idiom (`webDomains`, `inAppFeatures`). It needs no "hidden group" concept leaking into group
lists or the backup. Strict Mode compares two sets in a pure function (like
`ContentFilterAllowlistWeakening`). An uninstalled allowed app just stays in the list, which is
harmless, and it is allowed again if it's reinstalled.

`BlockRule.groupId` has no foreign key. Deleting a group deletes its rules and members, and removes
its id from every rule's `exceptGroupIds`, in one `@Transaction` repository call
(`deleteGroupWithRules`). Removing an allowed group only makes the All apps rule stricter, so that
half is free. Deleting the group's *own* enabled rule is gated as today. A stale id that somehow
survives (an import, a crash) resolves to no members, which fails strict.

`domain/model`:

- `BlockRuleData`/`ActiveRule` gain `allApps`, `exceptPackages: Set<String>`,
  `exceptGroupIds: Set<Long>` and `firstUnlockMinutes`.
- `GroupMembership` is replaced by a `GroupDefinition(id, categories, includes, excludes)` that the
  resolver reads.

### 5.2 Group membership is a rule, not a list

```
member(p, g) = !essential(p) && !(p in g.excludes) && ( p in g.includes || category(p) in g.categories )
```

- It is resolved **at evaluation time**, in a pure `domain/groups/GroupMembershipResolver`. Nothing is
  stored per installed app.
- **New installs join automatically, and nothing has to run when they're installed.** The next time
  the new app comes to the foreground, its category is read and it matches. No install receiver is
  needed for the engine.
- `category(p)` is `ApplicationInfo.category` (API 26 = minSdk, so no gate is needed). It is read
  through a `PackageFacts` port: `domain` declares the interface, the service/data layer implements
  it with `PackageManager`, it is cached per package, and it is read off-main.
- **Cache invalidation:** a runtime-registered receiver for `ACTION_PACKAGE_ADDED`, `_REPLACED` and
  `_REMOVED` (data scheme `package`). An update can change an app's category or its launcher
  activity. Manifest receivers for these are implicit broadcasts and don't fire for us since API 26.
  `QUERY_ALL_PACKAGES` is already declared, so these packages are visible.
- **Group editor UI:** `InstalledAppsRepository` gains `category` on its `InstalledApp` rows, from the
  same `ApplicationInfo` it already loads. The editor computes chip counts and member rows from that
  list with the same resolver, so the screen and the engine can't disagree. The repository calls
  `refresh()` on the same package receiver, so a new install shows up without restarting the app.
- **Coverage is unmeasured.** Developers set the category in their manifest, and many apps don't. The
  first task in PR 6 is a debug-only log of `(package, category)` for every launchable app on the
  bench device and two other phones. If big-name apps commonly report UNDEFINED, add a small curated
  `KnownAppCategories` map in `domain/groups/`, used **only** when the system reports UNDEFINED. It
  works like `PlatformSurfacesRegistry`: one file, with a test that every key is a well-formed
  package name. The chip counts make any gap visible to the user, either way.

### 5.3 First unlock (builds on #109)

Most of this mechanism is @danieltsam's #109 design, kept as-is:

- **The anchor:**
  - It is `ACTION_USER_PRESENT`, **runtime-registered** in the service next to the existing
    `ACTION_SCREEN_OFF` receiver.
  - Fallback: the first `TYPE_WINDOW_STATE_CHANGED` from an app window of the logical day, while
    `KeyguardManager.isDeviceLocked` is false. It covers devices with no secure lock, and missed
    broadcasts.
  - Whichever arrives first sets the stamp. (#109)
- **Always stamp, even with no first-unlock rule.** Then turning a rule on mid-morning has a real
  anchor and behaves predictably. (#109)
- **Wall clock, not elapsed-realtime.** The window is "8:12 to 8:42" in the user's terms. A stamp in
  the future (the clock moved back) is discarded. A clock jump forward fails open: the window just
  ends sooner. (#109)
- **No timers.** The window is checked on events: `now < stamp + minutes`. When the window ends, the
  next event lets the app through. (#109)
- **Logical day:**
  - The day runs from `day_start_minute` to `day_start_minute` the next calendar day.
  - The stamp is the first unlock **at or after** the current logical day's start.
  - On every event: if the stored stamp is before the current logical day's start, it is stale, and
    the next unlock replaces it.
- **The rule's days** are checked against the logical day's ISO weekday.

New in this spec:

- **Arming.** A first-unlock window counts only if `stamp >= rule.armedAt`. `armedAt` is set on
  every save and every switch-on. So a rule made, edited or re-enabled at 8:05, after an 8:00 unlock,
  starts **tomorrow**, and the editor says so ("Starts at your next first unlock."). Without this,
  saving a Morning Lock ten minutes after waking would lock the phone mid-setup, with no warning.
  Open question Q1 asks the owner whether to keep this.
- **One pure function:**
  `FirstUnlockWindow.isActive(now, stamp, dayStartMinute, minutes, days, armedAt, zone): Boolean`
  plus `endsAt(...)` for "Until 8:42 AM". It lives in `domain/engine/`, next to
  `ScheduleEvaluator`, which delegates to it when `firstUnlockMinutes != null`.
- **Proving `USER_PRESENT` on Android 16 first:** #109 proposed a log-only spike. It is kept as PR 5a:
  register the receiver, log every delivery (with screen-on, lock-type and boot context) and nothing
  else. Ship it to the bench device, and confirm delivery after a secure unlock, after a swipe-only
  unlock, after a reboot, and with Smart Lock / trusted places. Only then build PR 5b on top.

### 5.4 Essentials: one list for Nuke, groups and All apps

Today `NukeSafetyFloor.deviceFloor()` holds:

- Nudge itself
- `SYSTEM_PACKAGES`
- `IME_PACKAGES` plus the enabled keyboards
- the `StrictModeEscapeGuard.SETTINGS_PACKAGES`
- every `CATEGORY_HOME` launcher
- the default dialer (`TelecomManager`)

It is cached for 5 minutes and read off-main. This becomes the one essentials list, renamed
`EssentialApps` (with `NukeSafetyFloor` kept as a thin alias, or inlined, whichever reads cleaner in
the diff), and gains:

| Added | How it's resolved | Why |
|---|---|---|
| Default SMS app | `Telephony.Sms.getDefaultSmsPackage` (#109) | Codes, 2FA texts, emergency texts |
| Clock / alarms | the resolver(s) of `AlarmClock.ACTION_SHOW_ALARMS` | Turning off an alarm during a morning lock must never be blocked |
| Emergency / personal safety | the resolver of `TelephonyManager.ACTION_EMERGENCY_ASSISTANCE` when present, plus a short static list (e.g. `com.android.emergency`, `com.google.android.apps.safetyhub`) | Emergency info, crisis alerts |
| Enabled accessibility services' packages | `EnabledAccessibilityServices` (already in `domain/health`) | A screen reader must never be blocked |
| Installed autofill services | resolvers of the `android.service.autofill.AutofillService` service intent (not the `autofill_service` secure setting, which may not be readable at targetSdk 36) | Password managers show their unlock screen while filling a field inside an allowed app, so blocking them breaks the allowed app |

- **Nuke gets the same additions,** and its screen sentence becomes "Your phone, messages, clock,
  launcher, keyboard and Settings are never on this list." That is one sentence for one list.
- **Not essential:** Camera, Maps, the browser and banking apps. The user can allow any of them in
  seconds. Q2 asks the owner whether Camera should be on the list by default.
- **The "Always allowed" sheet** (3.4) renders this exact set, so what the user is shown and what the
  engine skips can't differ.

### 5.5 Browsers

Browsers bypass `EvaluateBlockUseCase.invoke`. `evaluateForegroundPackage` sends any package in
`WebDomainDetector.BROWSER_URL_BAR_IDS` to `evaluateWebDomain` instead, unless it's nuked. So today a
browser gets website rules, never whole-app rules. @danieltsam found this in #109, and it is the
escape hatch for any "block everything" feature: open Chrome, go to youtube.com.

**Rule:** a browser covered by a **group or All apps rule** is evaluated as an app first, exactly as a
nuked browser is. If that gives a block, the block applies to the whole browser. Otherwise website
evaluation continues as today. Direct per-app rules on a browser keep today's behaviour (out of
scope, follow-up F5).

**Implementation:**

- A service-level check, `isCoveredWholeNow(pkg)`, mirrors `isNukedNow`. It answers from the same
  cached rule snapshot (5.7) and is placed at the four sites #109 identified:
  1. `evaluateForegroundPackage`: the browser branch (`if (!nuked && isBrowser(pkg))`)
  2. `handleWindowContentChanged`: the content-change re-evaluation path for browsers
  3. `detectAndEvaluateFeature`: so a covered browser doesn't fall through to feature detection
  4. `grantAliveFor`: so a web-domain grant doesn't keep a covered browser open
- **Custom Tabs** opened from an allowed app run in the browser's package, so they're blocked during
  Morning Lock unless the browser is allowed. That is expected, and the "Blocks 47 apps" count
  includes the browser, so it isn't a surprise. Listed under risks (6.5).

### 5.6 Strict Mode

There is one principle: **an edit that makes a rule cover fewer apps, or block less, needs the
challenge.** It is enforced in pure `domain/lock/` functions, called through `StrictModeGate.run` in
the ViewModels, as today.

| Edit | Weakening? | Where |
|---|---|---|
| Add a rule of any kind | No | (rules only add) |
| Turn a rule off, delete it, or soften its mode/delay | Yes | `RuleWeakening` (today) |
| All apps: add an app to the allowed list | **Yes** | `RuleWeakening`: `exceptPackages` grew (set diff) |
| All apps: add an allowed group | **Yes** | `RuleWeakening`: `exceptGroupIds` grew |
| All apps: remove an allowed app or group | No | |
| Group: a member **leaves** (remove an app, add an exclusion, uncheck a category, delete the group) while the group's own enabled rule blocks it | **Yes** | new `GroupWeakening.isWeakening(old: GroupDefinition, new: GroupDefinition, usage: GroupUsage)`, comparing *resolved* membership on the device's installed apps (so unchecking a category that matches 0 apps is free) |
| Group: a member **joins** (add an app, check a category, restore a removed app) while an enabled All apps rule lists the group as allowed | **Yes** | same function. `GroupUsage(blocks: Boolean, allowedBy: Boolean)` is computed from the rule snapshot, so the screen never works it out. |
| Group used both ways, any membership change | **Yes** | same function |
| Group not used by any enabled rule, any change | No | |
| First unlock: fewer minutes, or switch the rule to a different schedule kind | **Yes** | `RuleWeakening`: new `firstUnlockMinutes` axis. Any change of schedule kind counts as weakening, the simple, safe reading. |
| Change "New day starts at" while any enabled first-unlock rule exists | **Yes** (either direction) | `SettingsWeakening` gains the toggle. Moving the start can push today's unlock into yesterday and skip today's window. |
| Change the target of an existing rule (app ↔ group ↔ all apps) | Not possible in the UI | Targets are fixed at creation. You delete and re-create, which is gated. |

Note for reviewers: `RuleWeakening` doesn't treat *days-and-times* edits as weakening today
(shortening a window is free). This spec doesn't change that. It is out of scope, so follow-up F6
(raise it separately).

### 5.7 Performance: one snapshot, no per-event database reads

Today every evaluation runs `resolveActiveRules`: all enabled rules, all groups, and then each
group's members, each one a Room `Flow.first()`. All apps rules make that worse, because *every*
foreground package now has at least one candidate rule.

- **`RuleSnapshotSource`** (data layer, a `@Singleton`) combines the rules, groups and members Flows
  into a `StateFlow<RuleSnapshot>`, rebuilt only when a table changes. `EvaluateBlockUseCase` reads
  `snapshot.value`. It needs one new DAO query: `AppGroupDao.getAllMembers(): Flow<List<AppGroupMember>>`.
- **`PackageFacts`** (5.2) caches `category` and `launchable` per package.
- **`EssentialApps`** keeps the existing 5-minute cache.
- **Per-event cost:** in-memory set lookups over a handful of rules. Coverage for one package is
  O(rules + groups-of-p).
- **Test:** the L3/L2 tests count DAO calls through a fake, and assert zero DAO reads per evaluation
  after the snapshot is warm.

### 5.8 Backup export / import

The envelope stays **version 1**. Older importers reject `version > 1` outright, while they ignore
unknown keys, so every addition is additive and is read with `opt*` defaults (the existing pattern
for `history` and `settings`).

- **`ExportedGroup`:**
  - gains `categories: List<String>?` (names, e.g. `"SOCIAL"`; unknown names are skipped on import),
    `includes: List<String>?` and `excluded: List<String>?`.
  - **`members` keeps holding the resolved members at export time.** An older Nudge then still gets
    a sensible hand-picked group.
  - A newer Nudge uses `categories`/`includes`/`excluded` when `categories` is present, and ignores
    `members`.
- **Rules an older Nudge would misread go in a new top-level array, `extendedRules`.** That means
  any rule with `allApps` or `firstUnlockMinutes`. It has the same `ExportedRule` shape, plus
  `allApps`, `exceptPackages`, `exceptGroups` (group **names**, because ids don't survive a move to
  another phone) and `firstUnlockMinutes`. On import, names resolve after the groups are imported. A
  name with no matching group is dropped, which only makes the rule stricter. An older importer ignores the key, so it
  skips these rules instead of misreading them. Two cases show why:
  - A first-unlock *group* rule placed in `rules` would import into an older Nudge with no schedule,
    which means **always on**.
  - An `allApps` rule would import with no target (inert, but confusing).
- **`ExportedSettings`** gains `dayStartMinute: Int?`.
- **`armedAt`** isn't exported. Import sets it to the import time, so an imported Morning Lock starts
  at the next first unlock.
- **Duplicate detection** (today: target + mode + schedule) includes `allApps`, the
  `exceptPackages` set, and `firstUnlockMinutes`.
- **The import Strict Mode gate:** today the whole import is gated when `weakensProtection` (rules
  and settings). It gains:
  - `ImportRulesUseCase` today **merges members into existing groups by name** (`addToGroup`). That is
    still free. An incoming `excluded` list for an **existing** group that an enabled rule uses is
    weakening, as is an incoming `categories` list that is a subset of the existing one (merge is a
    union, so this can only happen if a future import ever *replaces* the list; the check is there
    so it can't start silently). Both go through `GroupWeakening`, evaluated against the merged
    result.
  - A `dayStartMinute` change while a first-unlock rule is enabled is weakening
    (`ImportedSettingsWeakening`).
- **Nuke** is still never exported (today).

### 5.9 Package installs and removals

| Event | Effect |
|---|---|
| A new app in a checked category is installed | It is a member the next time it's evaluated, and shows in the group editor after the next `InstalledAppsRepository.refresh()` (on the package receiver). |
| A new app is installed while an All apps rule is on | Covered (it is "all apps"). The "Blocks N apps" count goes up. This is intended. |
| An allowed app is uninstalled | It stays in `exceptPackages`, which is harmless. It is hidden from the editor's list until reinstalled. |
| An app update changes its category | The cache is invalidated on `PACKAGE_REPLACED`, and membership follows the new category. Hand-added and removed-by-you choices win, as always. |
| A group member is uninstalled | Its member row stays (it costs nothing). It is hidden from the editor, and comes back if reinstalled. |

---

## 6. Risks

### 6.1 Locking yourself out (All apps)

| Risk | Mitigation |
|---|---|
| Forgetting an app you need (bank, 2FA, authenticator) | "Blocks N apps" count; the daily 2-minute pass still works; the allowed list is always one tap from the block screen's rule line. |
| A new rule locking the phone the moment it's saved | Arming (5.3): first-unlock rules start at the next first unlock |
| "All the time" + Hard block | Explicit confirm dialog (3.4) |
| Essentials blocked | Never covered. The list is resolved on the device and includes the SMS role, clock, accessibility and autofill services (5.4). |
| Nudge itself blocked | Nudge is essential, so the user can always open it and edit (Strict Mode still applies to weakening edits, by design) |
| Settings | Essential; protected by `StrictModeEscapeGuard` exactly as today |
| Phone calls | The default dialer is essential; the in-call UI belongs to it. |
| Delivery failure of `USER_PRESENT` | The first-AppWindow fallback (#109) means the window still opens. If both fail, the rule just doesn't fire that day (fails open, never closed). |

### 6.2 Apps with no category, or the wrong one

- The chip counts show the gap.
- Hand-add and "Removed by you" handle any app.
- The curated fallback map (5.2) is added only if the device measurement shows it's needed.
- **"Social & communication" includes messengers** (WhatsApp, Signal). A user building "Social media"
  will see them in the member list and can remove them. The default SMS app is already excluded as
  essential.

### 6.3 Per-event performance

The snapshot, the facts cache and the essentials cache (5.7) are introduced in PR 1, *before* any
feature makes evaluation heavier. The DAO-read-count test pins it.

### 6.4 Android 14+ / 16

- **Context-registered receivers** for system broadcasts (`USER_PRESENT`, `PACKAGE_*`) are allowed and
  need no export flag, because they're system-only broadcasts. Register them through the same helper
  as `screenOffReceiver`. `lintDebug` is the gate for the API calls.
- **`USER_PRESENT` on Android 16** is unproven. That is why PR 5a ships before PR 5b.
- **`ApplicationInfo.CATEGORY_ACCESSIBILITY`** is API 31+. It is never offered, so there is no gate.
- **Private space (15+) and work profiles:** apps in another profile aren't in our launchable set, so
  All apps doesn't cover them. This is a known gap, documented in the user-facing docs, the same as
  for app rules today.
- **"Launchable" as the definition of "everything"** is deliberate. A default-deny over *every*
  package would block system plumbing an allowed app depends on: Play services sign-in, the photo
  picker, the share sheet, `permissioncontroller`. Those would break allowed apps in ways the user
  can't diagnose or fix.

### 6.5 Expected-but-surprising behaviour (write it in the docs, not the UI)

- **Custom Tabs and in-app browsers** that run in the browser's package are blocked unless the browser
  is allowed.
- **Share sheets *into* a blocked app** are blocked.
- **An allowed app can still be blocked by another rule.** The editor shows this (3.4).

---

## 7. Phasing

Each PR is useful on its own, is ordered by value and risk, and leaves the app shippable. The "JVM
first" order follows the project's test layers: L1 pure, L2 replay, L3 lifecycle, L4 data,
then L5 lint, then L6 bench device.

**Order: 0 → 1 → 2 → 3 → 4 → 5a (any time; it has no dependencies) → 5b → 6.** PR 4 + PR 5b
together are Morning Lock. Pick-up candidates for @danieltsam:

- **5a/5b:** first unlock, which is the #109 mechanism.
- **6:** category groups, which is self-contained.

### PR 0: Delete the dead rule editor, extract shared editor pieces (small, no behaviour change)

- **Scope:**
  - Delete `RuleEditorScreen`, `RuleEditorViewModel`, the `Screen.RuleEditor` route and their tests.
  - Move `InfoButton`, `TimeSelector`, the mode segmented row, the wait-time chips and the schedule
    section from `UnifiedAppConfigScreen` into `ui/components/rule/`.
- **Files:**
  - `ui/screens/rules/RuleEditor*` (deleted)
  - `ui/navigation/NavGraph.kt`
  - `ui/screens/config/UnifiedAppConfigScreen.kt`
  - `ui/components/rule/*.kt` (new)
- **Tests:**
  - The existing App config tests stay green.
  - A source-scan contract test asserts that no file names `RuleEditorScreen` and that `NavGraph`
    has no `rule_editor` route.
- **Device QA:** App config opens, saves and edits as before (one app per mode).

### PR 1: Engine foundation: snapshot, package facts, one essentials list

- **Scope:**
  - `RuleSnapshotSource`, `PackageFacts` (with a port in `domain`), `AppGroupDao.getAllMembers`.
  - Rename `NukeSafetyFloor` to `EssentialApps` (or alias it).
  - Add the SMS role, clock, emergency, accessibility services and autofill to it.
  - Nuke uses it. Update the Nuke screen sentence.
  - No user-visible feature besides Nuke's wider safe list.
- **Files:**
  - `domain/usecase/EvaluateBlockUseCase.kt`
  - `data/repository/RuleSnapshotSource.kt` (new)
  - `data/db/dao/AppGroupDao.kt`
  - `service/NukeSafetyFloor.kt` → `service/EssentialApps.kt`
  - `domain/nuke/NukePolicy.kt`
  - `ui/screens/nuke/NukeScreen.kt`
  - `di/*`
- **Tests:**
  - L1: `EssentialAppsTest`, run against fakes for each resolver: each source is included, and a
    resolver that throws drops only its own source.
  - L3: `EvaluateBlockUseCase` makes zero DAO reads per evaluation once warm.
  - The existing `EvaluateBlock*Test` and `BlockEngine*Test` stay green unchanged. They are the
    proof that behaviour didn't move.
- **Device QA:**
  - Nuke on with Messages and Clock on the list: both still open.
  - A snapshot rebuilds after a rule edit (the edit takes effect on the next open, with no restart).

### PR 2: Groups you can reach and build (hand-picked)

- **Scope:**
  - The group editor (3.3) *without* the category chips.
  - The shared app picker (3.6).
  - "+ Block several apps" goes straight to the group editor. The sheet (3.2) arrives in PR 4, when
    there's a second choice to offer.
  - Group rows on Manage Apps.
  - "Also covered by" on App config.
  - Browsers covered by a group are evaluated as apps (5.5).
  - `GroupWeakening` and the gates.
  - Delete the old `GroupScreen` two-state UI.
  - No migration (the schema already supports hand-picked groups and group rules).
- **Files:**
  - `ui/screens/groups/*` (rewritten)
  - `ui/components/AppPicker.kt` (new)
  - `ui/screens/apps/AppListScreen.kt` + VM
  - `ui/screens/config/UnifiedAppConfig*`
  - `domain/lock/GroupWeakening.kt` (new)
  - `data/repository/BlockRuleRepository.kt` (`deleteGroupWithRules`, `renameGroup`)
  - `service/NudgeAccessibilityService.kt` (`isCoveredWholeNow` at the four sites)
  - `domain/engine/RuleEvaluator.kt` (`buildRuleName` for groups)
- **Tests:**
  - L1: `GroupWeakeningTest`, covering remove / add / delete / not-in-use.
  - L1: `buildRuleName` for a group.
  - L1: the group summary text in `BuildSummaryTextTest`.
  - L2: a recorded browser session with a group rule covering Chrome blocks the app, with no web
    evaluation.
  - L3: a group rule plus a stricter app rule resolves to the strictest.
  - L4: `BackupImportGateTest` with a group used by an enabled rule.
- **Device QA:**
  - Create "Social" with three apps at Delay 15s: each app delays.
  - Remove one under Strict Mode: the challenge appears.
  - Put Chrome in a Hard block group: Chrome is blocked whole, on any site.
  - App config for a member app shows "Also covered by Social".
  - Delete the group: its rule is gone and the apps open freely.

### PR 3: One list: merge Active Rules into Manage Apps

- **Scope:**
  - Manage Apps sections (3.2), the shared summary format, switch semantics with the Strict Mode
    gate.
  - Point the Home tile here and fix its count.
  - Remove `ActiveRulesScreen`/VM and its route; backup stays in Settings only.
- **Files:**
  - `ui/screens/apps/*`
  - `ui/screens/rules/ActiveRules*` (deleted)
  - `ui/screens/home/HomeScreen.kt` + VM
  - `ui/navigation/NavGraph.kt`
- **Tests:**
  - L1: section building (pure: rules + apps → sections). Empty → first-run layout. Search matches
    group names.
  - `BackupEntryPointsContractTest` updated to the single entry point.
  - A Home tile count test.
- **Device QA:**
  - Fresh install: Manage Apps shows search, the one row, and "Apps".
  - With rules: sections in order.
  - Turning off under Strict Mode challenges, from every section.
  - The Home tile count matches the covered apps.

### PR 4: All apps except (this plus PR 5b is Morning Lock)

- **Scope:**
  - The migration (`allApps`, `exceptPackages`).
  - Coverage in `RuleEvaluator`.
  - The All apps editor (3.4) with "All the time" and "Days and times".
  - The second row in the "+ Block several apps" sheet.
  - Manage Apps "All apps" section.
  - The Home lock status card (3.8).
  - Block screen rule line, "Until", and the allowed-app icons (3.9).
  - Browsers covered (5.5).
  - `RuleWeakening` `exceptPackages` and `exceptGroupIds` axes.
  - Allowed groups: the `exceptGroupIds` column, the Groups section in the picker, the group rows in
    "Except these", "Allowed during" on the group editor, and `GroupUsage` in `GroupWeakening`.
  - Export `extendedRules` (with `exceptGroups` by name), import, and the gate.
- **Files:**
  - `data/db/entity/BlockRule.kt`, `data/db/NudgeDatabase.kt` (migration)
  - `domain/model/*`, `domain/engine/RuleEvaluator.kt`, `domain/engine/RuleCoverage.kt` (new)
  - `domain/lock/RuleWeakening.kt`
  - `ui/screens/allapps/*` (new)
  - `ui/screens/home/*`
  - `ui/overlay/*Content.kt`, `ui/overlay/BlockOverlayActivity.kt`
  - `data/export/*`, `domain/usecase/ImportRulesUseCase.kt`, `domain/usecase/ExportRulesUseCase.kt`
  - `service/NudgeAccessibilityService.kt`
- **Tests:**
  - L1: `RuleCoverageTest`. Launchable and not essential and not allowed → covered. Essential →
    never. Not launchable → never. Allowed but in a Hard block group → that group still blocks
    (the "rules only add" invariant).
  - L1: an invariant test over the whole category of targets. For every `RuleTarget` kind, adding a
    rule never turns a Block decision into Allow, for any package in a generated set.
  - L1: `RuleWeakeningTest` for an allowed list (apps or groups) that grew or shrank.
  - L1: `RuleCoverageTest` for allowed groups. A member of an allowed group is not covered. A member
    that joins later is not covered from then on. A stale group id covers as if absent.
  - L1: `GroupWeakeningTest` for every `GroupUsage`: blocks-only (leaving weakens), allowed-only
    (joining weakens), both (any change weakens), neither (free).
  - L4: `NudgeDatabaseMigrationTest` for the new version (old rows: `allApps = 0`,
    `exceptPackages` null).
  - L4: an export round trip. Plus the **older-reader test**: parse the new file with only the v1
    keys and assert the all-apps rule is absent, not present-and-targetless.
  - L2: a browser under an All apps rule.
  - L3: a nuked allowed app is still nuked.
- **Device QA:**
  - All apps except Spotify, Hard block, Days and times covering now: Instagram blocked, Spotify
    opens, Phone/Messages/Clock/keyboard/Settings open.
  - The block screen shows the allowed icons, and tapping Spotify opens it.
  - Chrome is blocked.
  - Install a new app: it's blocked.
  - The 2-minute pass works.
  - Home card shows and disappears at the window end.
  - "All the time" + Hard block shows the confirm dialog.
  - Adding an allowed app under Strict Mode challenges.
  - Allow the group "Work" (Calendar, Docs): both open, and "Blocks N apps" drops by 2. Add Slack to
    Work under Strict Mode: the challenge appears. After confirming, Slack opens.

### PR 5a: `USER_PRESENT` spike (log only, from #109)

- **Scope:** register the receiver, and log deliveries and the fallback signal. No behaviour.
- **Files:** `service/NudgeAccessibilityService.kt`
- **Tests:** a contract test that the receiver is registered and unregistered in teardown (mirrors
  `screenOffReceiver`).
- **Device QA (bench, Android 16):** read the logs after:
  - a PIN unlock
  - a swipe unlock
  - a reboot followed by an unlock
  - a Smart Lock unlock
  - an unlock with the screen-on but locked notification shade pulled
  - the first unlock after 4:00

  Decision: proceed to PR 5b, or adjust the anchor.

### PR 5b: First unlock schedule (with Morning Lock as the acceptance test)

- **Scope:**
  - The migration (`firstUnlockMinutes`, `armedAt`) and the preferences (`day_start_minute`,
    `first_unlock_at`).
  - `FirstUnlockWindow`, the stamping, and the "When" option in all three editors.
  - "Starts at your next first unlock", "Until …".
  - `RuleWeakening` minutes axis, `SettingsWeakening` day start.
  - Export into `extendedRules` and settings.
- **Files:**
  - `domain/engine/FirstUnlockWindow.kt` (new), `domain/engine/ScheduleEvaluator.kt`
  - `data/preferences/NudgePreferences.kt`
  - `ui/components/rule/WhenSection.kt`
  - the three editors
  - `domain/lock/*`
  - `data/export/*`
  - `service/NudgeAccessibilityService.kt`
- **Tests:** L1 `FirstUnlockWindowTest`:
  - the window boundaries
  - a 2:00 AM unlock belongs to the previous day
  - a stamp before the logical day start is stale
  - a future stamp is discarded
  - a clock jump forward ends the window early
  - arming (a stamp before `armedAt` doesn't count)
  - days use the logical weekday
  - DST spring-forward and fall-back days, with an explicit `ZoneId`
  - a time-zone change mid-window

  Plus:
  - The stamp is recorded with no rule present.
  - Migration test.
  - The older-reader export test: a first-unlock group rule is absent from the v1 view.
- **Device QA:**
  - Build Morning Lock (all apps except Spotify and Maps, Hard block, 30 min after first unlock).
  - The editor says it starts at the next first unlock.
  - Lock, wait past the next 4:00 boundary (or set the day start a few minutes ahead), unlock:
    everything but Spotify, Maps and the essentials is blocked, and the block screen says "Until"
    unlock+30.
  - After 30 minutes, apps open.
  - Re-locking and unlocking within the window keeps the same end time.
  - A second unlock after the window doesn't restart it.
  - Changing the day start under Strict Mode challenges.

### PR 6: Category groups

- **Scope:**
  - First, the debug-only category coverage log (5.2), and the decision on `KnownAppCategories`.
  - Then the migration (`categories`, `excluded`), `GroupMembershipResolver` and the category chips
    with counts.
  - "Removed by you", the package receiver plus cache invalidation, and `InstalledAppsRepository`
    category plus refresh.
  - `GroupWeakening` on resolved membership.
  - Export `categories`/`includes`/`excluded`.
- **Files:**
  - `domain/groups/GroupMembershipResolver.kt` (new)
  - `data/db/entity/AppGroup*.kt`, the migration
  - `data/repository/InstalledAppsRepository.kt`
  - `ui/screens/groups/*`
  - `service/*` (receiver)
  - `domain/lock/GroupWeakening.kt`
  - `data/export/*`
- **Tests:**
  - L1 resolver: category match, include, exclude-beats-category, essential-beats-everything, and
    UNDEFINED never matches.
  - L1 `GroupWeakening` on resolved sets: unchecking a 0-app category is free, unchecking a 5-app
    category is weakening.
  - L4 migration.
  - L4 export: an older reader sees `members` = the resolved snapshot.
  - `InstalledAppsRepositoryTest` with a category.
- **Device QA:**
  - Check "Social & communication": the count matches the member list.
  - Remove Instagram: it moves to "Removed by you".
  - Install a Social app from Play: it's a member without reopening the app, and it is blocked on
    first open.
  - Add back Instagram.
  - Under Strict Mode, unchecking the category challenges.

### What's deliberately not in any PR

Shared group daily budgets (F3), more than one rule per group (F2),
Nuke picking from groups, and a "focus mode" quick toggle. Each can come later without changing the
model above.

---

## 8. Owner decisions (2026-11-02)

1. **Groups in the allowed list: yes, in v1.** "All apps except my Work group" is built in PR 4
   (sections 1, 3.3, 3.4, 3.6, 5.1, 5.6). The direction stays on the reference, and `GroupWeakening`
   checks each group edit against every way the group is used.
2. **A new or re-enabled first-unlock rule waits until the next morning: yes.** This is the arming in
   5.3. Saving a Morning Lock after you've woken up doesn't lock the phone mid-setup. The editor says
   "Starts at your next first unlock."
3. **Camera is not on the always-allowed list.** Allowing it takes one tap, and some people want it
   out of reach in the morning. The fixed list is unchanged (5.4).
4. **Active Rules merges into Manage Apps: yes (PR 3).** The owner's bar for the merged screen: "good
   UI is very important." Section 3.2's layout and its progressive disclosure are the contract, and
   PR 3 isn't done until the device screenshots of an empty phone, a typical phone and a heavy setup
   all read cleanly.

---

## 9. Credits: what this builds on from #109 (@danieltsam)

- Browsers bypass `EvaluateBlockUseCase.invoke`, so any whole-app block needs the service-level
  check at four sites (`evaluateForegroundPackage`, `handleWindowContentChanged`,
  `detectAndEvaluateFeature`, `grantAliveFor`). Section 5.5.
- The essentials list isn't `SYSTEM_PACKAGES`, and the default SMS role must be added. Section 5.4.
- `ACTION_USER_PRESENT` must be runtime-registered, with a first-AppWindow + `!isDeviceLocked`
  fallback. Section 5.3.
- Always record the first-unlock stamp even when the feature is off. Section 5.3.
- Wall clock, not elapsed-realtime; discard a future stamp; fail open on clock jumps. Section 5.3.
- No timers: check `now < windowEnd` on events. Section 5.3.
- Hide the redirect bubble on these blocks. Section 3.9.
- Count these blocks as hard blocks in stats. Section 3.9.
- A log-only spike to prove `USER_PRESENT` on Android 16 before building on it. PR 5a.

Where this spec differs from #109: there is no separate Morning Lock feature (it's one rule, per the
owner's direction), and allowed apps don't override other rules (section 1, "Precedence").

---

## 10. Follow-ups (not scheduled)

- **F1:** (promoted into PR 4 by the owner, 2026-11-02) a group in an All apps rule's allowed list.
- **F2:** more than one rule per group (for example, Delay always plus Hard block 9–5), like App
  config's default + scheduled override.
- **F3:** a shared daily budget across a group's apps (today's limit is per app, labelled "each app").
- **F4:** whether a pass earned under a softer rule should end when a stricter days-and-times rule
  starts mid-sitting.
- **F5:** whole-app rules on browsers set directly on the browser's App config (today they're
  web-only).
- **F6:** treat shortening a days-and-times window as weakening under Strict Mode.
