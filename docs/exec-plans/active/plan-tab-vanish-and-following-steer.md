# Plan — Instagram Tab Vanish + experimental Following steer (v1.18.0)

Source: Anti 2026-09-22. Feasibility: `~/ops/routes/nudge/research/tab-vanish-and-following-only-feasibility-2026-09-22.md`.
Device spike (Instagram 447.0.0.55.81, Pixel 3, 1080x2160): `~/ops/routes/nudge/research/spike-2026-09-22/*.xml`.

## What ships

**A. Tab Vanish.** While a rule covering Instagram REELS resolves to HARD_BLOCK right now
(unconditional, or daily budget spent on a NONE+limit rule), Nudge draws a touchable
`TYPE_ACCESSIBILITY_OVERLAY` exactly over `clips_tab`'s bounds, painted the nav-bar colour. The icon
disappears and the tap is eaten. Additional to the existing player-container block, never a
replacement. DELAY/HOLD/BREATHING never vanish the tab — the tab must stay tappable to reach the
interstitial.

**B. Following steer (experimental, default OFF).** Per-rule opt-in. On arrival at Instagram's Home
feed, click the title/dropdown then the "Following" menu item — at most once per arrival, never a
loop, silent on failure.

**Generic seam.** `domain/surfaces/` — pure data describing one host app's surfaces. One
implementation (Instagram). YouTube is a documented roadmap line, not code.

## Measured facts the build is pinned to (spike, 2026-09-22)

- `tab_bar` [0,1896][1080,2028], stable across scrolling; **absent** on the Following screen, in the
  reel player, and in stories/DMs. `clips_tab` [216,1896][432,2028], content-desc "Reels".
- **Every tab reports `selected=false`** in the uiautomator dump. The cover therefore keys on
  `clips_tab` BOUNDS only and never on tab selection.
- Nav bar RGB measured (255,255,255) in light mode. Dark assumed (0,0,0). No runtime sampling
  available → `navBarColor(nightMode)`.
- Home feed signal: `title_logo` (ImageView, content-desc "Instagram Home Feed").
- Following steer: `title_logo` is NOT clickable — click its parent `action_bar_title_view`
  (ViewAnimator [132,97][948,210]); then the `context_menu_item` Button whose child
  `context_menu_item_label` reads "Following".
- Following is a SEPARATE full-screen activity: header `action_bar_title` == "Following", and
  `tab_bar`/`clips_tab` are gone (so Reels is unreachable from it — a bonus).
- **Deep links are dead.** `?variant=following` is claimed but ignored; `instagram://feed?variant=following`
  does not resolve. Click sequence it is.
- Warm relaunch resumes ON Following; cold start resets to Home.

## Where the state lives

| Concern | Home |
|---|---|
| Which nodes to find, per app | `domain/surfaces/PlatformSurfaces` + `InstagramSurfaces` (pure data) |
| Is the cover wanted right now | `BlockDecision.Block.tabVanish` — from the rule that DECIDED the block |
| Is the cover still valid | `TabCoverPresence.shouldKeep(signal, coveredPackage)` — pure, exhaustive over `ForegroundSignal` |
| Where to draw it | `TabCoverPlacement.of(bounds)` — pure |
| The window | `service/TabCoverOverlayManager` — views built from `AwarenessOverlayWindow` (issue #41 identity) |
| Which Instagram screen is showing | `HostSurface` classified from node presence — pure |
| Steer once-per-arrival | `domain/surfaces/FollowingSteer` — pure state machine, clock injected |
| Steer node taps | `service/FollowingSteerExecutor` |

## Data model (names fixed up front so lanes cannot diverge)

- `BlockRule.tabVanish: Boolean = true`, `BlockRule.followingSteer: Boolean = false`
- mirrored on `BlockRuleData`, `ActiveRule`, `ExportedRule` (same defaults)
- `BlockDecision.Block.tabVanish: Boolean = false` — set from the DECIDING rule, not `any {}`
  (grayscale keeps its existing `any {}` semantics; do not change it)
- DB 10 → 11, `MIGRATION_10_11`, two `ADD COLUMN`s
- Turning `tabVanish` off is a Strict-Mode WEAKENING axis in `RuleWeakening`

### Defaults, and why
Tab Vanish **ON** by default on every rule: it can only ever activate behind a decision that is
already a hard block, so it adds enforcement only where the user already asked for the strongest
one. Following steer **OFF** by default: it is the first time Nudge acts *inside* another app.

## Counting invariant
A cover-eaten tap writes NOTHING. It is not a confrontation: no `UsageEvent`, no `wasBlocked` row,
no arrival claim (LESSONS 2026-09-20, `BlockedCountSemanticsContractTest`).

## Lifecycle seam
The cover is torn down from `applyForegroundSignal` — the one place that sees every classification
above every early return — as its THIRD consumer, never from the six exits that each mean "the user
is somewhere else". `AwarenessOverlay`/`Transient`/`PipOnly`/`NotForeground` make no claim about the
foreground and must not tear it down (the cover is itself an `AwarenessOverlay`).

## Steer policy (echo in the PR)
Steer on Home-feed arrival EXCEPT when the user reached Home by backing out of Following on purpose.
Encoded as: observing FOLLOWING marks the arrival attempted; only leaving Instagram or visiting
another tab resets it. One attempt per arrival, ~1.5 s bounded wait for the menu, give up silently,
one debug log line per attempt and outcome.

## Lanes (disjoint files, one worktree)

- **Wave 1** — A: data/model/migration/export/weakening. D: the pure `domain/surfaces/` package.
- **Wave 2** — B: rule-editor toggles. C: overlay manager + node finder + steer executor.
- **Integrator**: `NudgeAccessibilityService` wiring, docs, CHANGELOG, version, gates, PR, QA dispatch.

## Test layers (docs/testing-strategy.md)
- **L1** pure JVM: placement, presence, surface classification, steer state machine, engine
  `tabVanish` propagation, adapter registry, migration SQL, export/import round trip, weakening
  axis, view-model save.
- **L2 (fixture replay)** — the spike's real `*.xml` dumps become committed fixtures: a tiny
  hierarchy parser feeds the SAME locators and the SAME surface classifier used in production.
  This is the layer the repo's own strategy doc says is underfunded; both features are node-tree
  features, so it is the right one.
- **Source-level contract**: cover views carry the awareness identity; the cover manager never
  touches usage logging; Instagram ids appear only inside `InstagramSurfaces`.
- **L6** bench Pixel via `device-tester`, 7 cases, before/after screenshots.

## Out of scope
YouTube Shorts tab vanish + Subscriptions steer (roadmap line only). TikTok. The web extension.

## Open item carried from the spike — RESOLVED

`BlockOverlayActivity` flashed for ~1 s on Instagram launch during the 2026-09-22 spike. **Not
reproducible** on the 1.18.0 QA build: two cold starts with debug logging on, no flash. The spike ran
against leftover rules from the #35 QA session, so the most likely reading is that it was a real block
firing on those rules rather than anything this feature introduced. Closed rather than carried — if it
returns, the capture to take is a logcat around the launch with debug logging enabled, because the
`i`-level "handling block" and "block overlay launch dropped" lines name both the rule and the reason.
