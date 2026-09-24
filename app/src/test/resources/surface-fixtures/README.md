# Surface fixtures — real Instagram view hierarchies, scrubbed

Eight recorded view-hierarchy dumps, replayed by the tests in
`app/src/test/java/com/astraedus/nudge/domain/surfaces/`. They are the L2 layer of
`docs/testing-strategy.md` applied to the two node-tree features in `domain/surfaces/`: the same
`NodeLocator`s and the same `InstagramSurfaces.classify` that run in production are driven against a
real device's tree, so a fixture can **contradict** the code instead of agreeing with it.

## Provenance

| | |
|---|---|
| App | Instagram **447.0.0.55.81** |
| Device | Pixel 3, Android 12 (API 31), 1080x2160 |
| Captured | 2026-09-22 |
| Raw dumps | `~/ops/routes/nudge/research/spike-2026-09-22/*.xml` (not in this repo) |
| Scrubber | `scripts/scrub-surface-fixture.py` |

## The scrubbing rule

**The raw dumps are a transcript of one real person's feed.** They contain usernames, captions,
story labels ("<name>'s story, 0 of 27, Unseen."), ad copy and like counts, in the `text` and
`content-desc` attributes. This repository is PUBLIC, so the raw files can never be committed.

The scrubber keeps **structure byte-for-byte** — `class`, `resource-id`, `bounds`, `clickable`,
`selected`, `index`, `package` and every other attribute are untouched — and **blanks every `text`
and `content-desc` value**, except for a closed allowlist of the strings this feature reasons about:

```
Following   Favorites   Instagram Home Feed
Home        Reels       Message   Search and explore   Profile
```

Those eight are Instagram's own chrome: the five bottom-nav tab content-descriptions, the Home-feed
logo's description, and the two title-dropdown menu labels. Nothing else survives.

It is an **allowlist, not a denylist**, deliberately. A denylist has to anticipate what a caption
looks like, and it is wrong the first time somebody's username is "Following".

`SurfaceFixturePrivacyTest` walks every committed fixture and fails on any `text` or `content-desc`
value outside that allowlist, so an un-scrubbed dump cannot be committed quietly. It is the same
invariant `A11yCapturePrivacyTest` holds over `app/src/test/resources/a11y-captures/`.

To re-scrub after a new spike:

```bash
scripts/scrub-surface-fixture.py <raw>/*.xml --out app/src/test/resources/surface-fixtures
```

## The fixtures

| File | What it is | What it pins |
|---|---|---|
| `home.xml` | Instagram Home feed, at the top | `HOME_FEED` via `title_logo`; `clips_tab` bounds `[216,1896][432,2028]`; every tab reports `selected=false` |
| `home-scrolled.xml` | The same feed, scrolled down | Still `HOME_FEED` — and **`title_logo` is gone**: the action bar scrolls away, which is why `InstagramSurfaces.classify` needs the `sticky_header_list` rung |
| `following-feed.xml` | The Following screen | `FOLLOWING_FEED` via `action_bar_title` == "Following", and **no `clips_tab` node at all** — the cover must hide here because there is no nav bar to cover |
| `relaunch.xml` | Warm relaunch of Instagram | Resumes ON Following, so `FOLLOWING_FEED` again |
| `logo-menu.xml` | The title dropdown, open | The "Following" row is findable via `SteerRecipe.menuItemLabel`; rows share one id and only the label separates them |
| `coldstart.xml` | Cold start of Instagram | Resets to `HOME_FEED` |
| `variant-link.xml` | After `https://www.instagram.com/?variant=following` | `HOME_FEED` — **recorded proof the deep link does not work** |
| `scheme-link.xml` | After `instagram://feed?variant=following` | `HOME_FEED` — same, for the custom scheme |

The last two are why the steer is a click sequence and not a deep link. The parameter is claimed by
the app and then ignored; the scheme does not resolve. Both land on Home, and these files are the
evidence rather than a claim in a commit message.

## Reading them in a test

Use `SurfaceFixture` (test sources, same package). It parses with a regex, yields plain
`resource-id` / `class` / `text` / `content-desc` / four `Int` bounds per node, and adds no Gradle
dependency.

**Never construct an `android.graphics.Rect` in test sources.** `unitTests.isReturnDefaultValues` is
deliberately NOT set in `app/build.gradle.kts`, so every `android.*` call in a JVM test throws — and
setting it would turn every unmocked android call across ~1550 existing tests into a silent `0`/null.
Bounds are parsed straight to four ints and handed to `TabCoverPlacement.of(left, top, right, bottom)`.
