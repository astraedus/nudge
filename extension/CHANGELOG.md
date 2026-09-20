# Nudge for Chrome — changelog

The extension versions independently of the Nudge Android app and tags as `ext-v*`, so it
keeps its own changelog here rather than sharing the repo-root `CHANGELOG.md`. Two
independently-numbered products in one file invites the reader to match a `v1.17.0` Android
release against a `0.2.0` extension release and conclude something about both; the tag
namespaces were already split for exactly that reason.

## 0.2.0 — unreleased

Per-site everything. A rule is now the unit: how the whole site behaves, how long the pause
is, the daily budget, the schedule, whether the site is grayscaled, and — for known
platforms — each of the site's *features* with its own gate mode, delay and budget.

### Added

- **Allow mode.** A rule no longer has to block. `Allow` means the site opens normally and
  the rule exists to carry a daily limit, grayscale, or feature gates. This makes a
  limit-only rule expressible for the first time.
- **"Block only during work hours."** A schedule window's mode can now also be `Allow`, in
  either direction — so an Allow rule with a blocking window, and a blocking rule with an
  allowance window, are both expressible. This closes the schedule-polarity gap the previous
  release listed under Known gaps.
- **Grayscale is per site.** Every rule carries its own toggle, still flash-free (one
  dynamic CSS registration derived from the set of gray domains). YouTube keeps its colour
  reward for allowed channels.
- **Site features for seven platforms** — YouTube, Instagram, TikTok, X/Twitter, Facebook,
  Reddit, LinkedIn. Feed and reel surfaces can be gated by URL with their own mode, pause
  and daily budget; individual page elements (stories trays, trends, who-to-follow,
  suggested blocks, nav entries, comments) can be hidden.
- **Feature budgets.** A surface can carry its own daily limit — "ten minutes of Shorts a
  day, YouTube itself unlimited" — tracked in its own usage bucket.
- **Block YouTube by default, allow these channels** now works end to end. With the
  youtube.com rule blocking and a channel whitelist active, allowed channels' `/watch` and
  channel pages pass the network layer, everything else is gated by the site's own mode, and
  the block page lists the allowed channels as links — ahead of the Escape Hatch, so nobody
  burns their once-a-day pass reaching a channel they were never blocked from.
  A **spent daily limit still closes the site**, allowed channels included: the limit
  budgets how much of the site you get, the list decides what counts. Otherwise "an hour of
  YouTube a day" would be unlimited for allowed channels, and the limit would only ever bite
  the videos you had already asked for less of.
- Quick-add chips for the seven platforms, and a "Trim the feeds" row in onboarding.
- A grayscale quick toggle in the popup, gated by Commitment Lock like every other
  weakening.

### Changed

- The separate YouTube dashboard tab is gone. Its controls are the youtube.com rule's
  feature section, where they belong: they were previously disconnected from the site rule
  that also governed YouTube, so the two could contradict each other.
- **An unidentified YouTube channel now resolves to the site's default** instead of always
  failing open. On an Allow-mode site it still fails open with a distinct
  `reason: 'unknown-channel'`; on a site the user has set to block, it now fails closed to
  that site's mode, because silently opening a site the user said to block defeats the rule
  with no signal — and the pause and Escape Hatch still exist. Both directions are tested.
- Commitment Lock's weakening detector covers every new field: grayscale, the schedule
  window's own mode, gate modes, pauses and budgets, hide toggles, and the channel lists —
  where adding to a whitelist and removing from a blacklist are both weakenings.
- `GET_SITE_CONFIG` replaces `GET_YOUTUBE_CONFIG`; every platform content script asks the
  worker the same question and gets an already-resolved answer, so an in-page overlay and a
  network-layer redirect can never disagree about the same surface.

### Migration

Settings migrate from v2 to v3 automatically on update. The top-level YouTube block folds
into the youtube.com rule: an existing rule keeps its mode, pause, limit and schedule and
gains the features; if there was no youtube.com rule and any YouTube feature was on, one is
created in Allow mode. The fold merges rather than overwrites, taking the stronger value on
every axis, so it is idempotent and safe against a browser that has already upgraded syncing
against one that has not.
