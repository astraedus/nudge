# Nudge for Chrome — changelog

The extension versions independently of the Nudge Android app and tags as `ext-v*`, so it
keeps its own changelog here rather than sharing the repo-root `CHANGELOG.md`. Two
independently-numbered products in one file invites the reader to match a `v1.17.0` Android
release against a `0.2.0` extension release and conclude something about both; the tag
namespaces were already split for exactly that reason.

## 0.4.1 — 2026-10-23

### Added

- **Creator mode links on the block page.** With "Allow my channel and YouTube Studio" on,
  the block page now has "Open YouTube Studio" and, if you set "Your channel", "Go to your
  channel (@handle)", next to your allowed channels. Before, it showed the allowed channels
  and nothing about Studio, so you had to type the address yourself.

### Fixed

- **A channel you added by @handle keeps the channel ID Nudge learned for it.** Visiting the
  channel teaches Nudge its ID, so its `/channel/UC…` address is allowed or blocked like the
  @handle. Saving any change from a dashboard tab that was already open before that visit
  wrote the old handle-only entry back and threw the ID away. Saves now keep every ID Nudge
  has learned, and the dashboard shows the saved entry afterwards. The Commitment Lock still
  judges exactly the change you made.

## 0.4.0 — 2026-10-23

### Added

- **Creator mode for YouTube.** A new checkbox in the YouTube rule, "Allow my channel and
  YouTube Studio", keeps YouTube Studio (studio.youtube.com) and the youtube.com pages that lead
  to it (Upload, Go live, the dashboard, sign-in) open while YouTube is blocked. They stay
  open after your daily limit is spent. Time spent there is not counted against the limit.
  The optional "Your channel" field lets your own channel and its videos through as if it
  were on your list. With the Commitment Lock on, turning creator mode on (or changing your
  channel while it is on) needs the challenge. Turning it off never does.

### Fixed

- **Videos from an allowed channel play when you open them from the channel's page.**
  Clicking a video on an allowed channel's page showed "Nudge couldn't identify this
  video's channel" and then left the video paused at 0:00. Nudge was deciding before
  YouTube had drawn the video's owner. It now waits quietly for the owner (about two
  seconds, with the video held and no message), then lets the video play.
- **A paused video starts again once Nudge is done with it.** After a Delay or Breathing
  pause finished, or when going Back to an allowed video, the video used to stay paused
  until you pressed play yourself.
- **Back to an allowed video no longer says "This channel is off your list".** Nudge was
  reading channel links from parts of the page YouTube keeps hidden, so it could name the
  wrong channel.
- **Clicking around inside YouTube is held to the same rule as opening a page directly.**
  Under "block YouTube except these channels", the YouTube logo, search, Subscriptions,
  Shorts and other channels' pages opened freely if you reached them by clicking from an
  allowed video. They now go to the block page, as they always did when typed into the
  address bar.
- **More of an allowed channel is reachable.** Live streams (`/live/...`), playlists, and the
  old `/c/name` and `/user/name` channel addresses no longer go to the block page. The old
  addresses are checked against the channel the page belongs to. Each allowed channel's
  `@handle` and `/channel/UC...` addresses now both work as soon as you visit the channel
  once, not only after watching one of its videos.

## 0.3.2 — 2026-10-05

### Fixed

- **The Breathing pause is a smooth breath now, with a real countdown.** The circle used to
  move in small jerks (the block page stepped it ten times a second through a short linear
  transition, so it slammed straight into reverse at the top of every breath) and the in-page
  overlay's circle started a beat behind its own "Breathe in" label. The circle now grows
  continuously over the whole 4-second inhale and shrinks over the whole exhale, easing gently
  at the turn, and each phase counts down 4, 3, 2, 1 in its centre, landing on 1 as the phase
  ends. The circle, the numbers, the progress bar and the moment the pause completes all run
  off one clock, so they cannot drift apart. Same on the block page and on the in-page gate
  (YouTube Shorts, Instagram Reels and the rest). The pause still lasts exactly as long as
  your rule says.
- **Switching tabs mid-breath no longer jumps.** The pause holds while its tab is hidden and
  picks up exactly where it left off when you come back.
- **Reduced motion is respected.** With "reduce motion" on in your OS, the circle stays still
  and gently brightens and fades instead of growing and shrinking; the countdown is unchanged.

## 0.3.1 — 2026-10-04

### Fixed

- **"Nudge hit an internal error blocking this page" on YouTube channel pages.** Any URL
  with an `@` in its path (`youtube.com/@handle`, TikTok's `/@user/video/...`) was parsed as
  if the `@` began URL userinfo, so the host came out as the handle and no rule matched. The
  network layer redirected the page correctly, the block page then found "no rule here",
  bounced back to the site, got redirected again, and the loop guard stopped with the
  internal-error message. The same mis-parse meant time on those pages was never counted
  against the site's daily limit. The host is now read from the URL's authority only.
- **Stale network rules heal themselves.** Redirect rules persist across browser restarts
  and sleep, so a "limit reached" redirect from yesterday could still be installed when a
  tab was restored the next morning. When the block page finds nothing applies, Nudge now
  recompiles its rules before sending you on, so the bounce goes through instead of looping.
  Rule recompiles are also serialized, so a browser start can no longer race two of them.

## 0.3.0 — 2026-09-30

### Added

- **Count-based daily limits on short-form surfaces.** "20 Shorts a day, then the gate."
  Alongside the existing minute budget, a gate whose content is an item stream can now
  carry a limit in ITEMS: YouTube Shorts, Instagram Reels and TikTok's For You feed. One
  increment per DISTINCT item seen today, observed through SPA navigation, so swiping back
  to the one you were just on does not spend the allowance twice. The two axes are
  independent: set either, both, or neither, and the gate closes the moment EITHER is
  spent. The block page says which one it was ("You've watched 20 Shorts today"), the rule
  editor offers the control only for surfaces where one item is actually well defined, and
  the dashboard's per-surface line now reads `Shorts: 12m (34 Shorts)`. Raising or removing
  a count is a weakening, so the Commitment Lock gates it exactly as it gates minutes.
  No competitor ships this (StayFree, ScreenZen and Intention all stop at time).
- **A channel you added learns its other name.** A channel list entry only ever held the
  identifier you typed — `@veritasium` stored a handle and no id, a pasted
  `/channel/UC...` URL stored an id and no handle. Watching one of that channel's videos now
  teaches the entry the identifier it was missing, and the real channel name in place of the
  `@handle`/id placeholder. That closes a hole you could actually hit: until the entry had an
  id, a full navigation to the channel's own `youtube.com/channel/UC...` page was redirected
  to the block page even though you had explicitly allowed that channel, because the network
  layer can only carve out the identifiers the entry holds. The same channel added twice by
  two different routes is merged into one row. Nothing is ever added to your list and nothing
  is ever removed from it, so this never weakens a rule and never asks for a Strict Mode
  challenge; an observation that contradicts a stored entry is refused outright rather than
  merged.
- **The channel list shows what it actually knows.** Each row leads with the channel's name
  and carries the identifiers known for it — the `@handle`, the `UC` id, or both — on a
  quieter second line, so a list that used to read as a column of raw ids becomes legible.
  A row never prints the same string twice.

### Changed

- **"I changed my mind" on a blocked YouTube channel now takes you back where you came
  from.** It used to go to youtube.com, which is exactly what a "block YouTube except these
  channels" rule redirects — so the button that means "get me out of here" put you on the
  block page. It now goes back a page, and closes the tab when there is no page to go back
  to (a tab opened straight onto the video).

### Fixed

- **A gate with a spent minute budget could offer a pause that bought access the block page
  would have refused.** Two layers were describing one surface differently: the in-page gate
  asked the gate's MODE first and rendered a Delay countdown, while the block page's engine
  independently escalated the same exhausted budget to a Hard Block. `gateAppliesNow` now
  checks both budget caps before the mode, so the one predicate answers for both layers, and
  it answers with the stronger verdict.
- **The pause screen on Instagram, TikTok, X, Facebook, Reddit and LinkedIn now covers the
  page.** It was rendering as a block of text pushed into the top of the feed instead of a
  full-screen interstitial, because its stylesheet was keyed to an element name the code had
  stopped using. YouTube was unaffected.

## 0.2.0 — 2026-09-20

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
