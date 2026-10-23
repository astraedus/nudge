# Plan: Nudge for Chrome, a YouTube whitelist that works, and Creator mode

Branch `worktree-agent-a9da64c289bc5d3f4`, scope `extension/` only. No version bump; CHANGELOG "Unreleased".

## Root causes found on live youtube.com (before any fix)

1. **Channel page to video = paused forever (the core bug).** The channel page has no element any
   watch-byline selector matches, so `previousChannelKey` is null at the hop. For 1.6-2.8s after the
   click the watch page has no owner byline. Detection returns null, `channelFreshness` calls that
   CONFIRMED (because previous is null), and `decideWatchGate` fails closed with "couldn't identify
   this video's channel". The overlay goes up and the media is held.
2. **`MediaHold.release()` never resumes.** When the verdict flips to allow, the overlay goes but the
   video stays paused at 0:00, and after a completed pause too.
3. **Watch-page DOM detection is unscoped.** It reads anchors from the whole document: hidden pages
   that `ytd-page-manager` keeps around, description links, the sidebar. `assembleChannel` can weld
   one channel's handle to another's id. Back to an allowed video showed "off your list".
4. **No in-page gate off `/watch`.** In-SPA home, search, a non-listed channel page and Shorts were
   all reachable, while a full load of the same URL went to the block page.
5. **Network allow list too narrow.** It was missing `/live/<id>`, `/c/` and `/user/` (legacy channel
   URLs, which do not equal the handle) and `/playlist`. A handle-only entry did not open
   `/channel/UC..` (or the reverse) until something taught the other axis.

## Fixes (layer, then test)

- `content/channelDetection.ts`: scope the watch DOM tier to the visible `ytd-watch-flexy` and its
  owner renderer. Expose the flexy's `video-id`. Live capture shows it updates in the same 25ms tick
  as the byline, so it is the freshness signal for the DOM tier.
- `core/channelFreshness.ts`: `domVideoId` mismatch means SETTLING. A null detection inside the settle
  window after an SPA hop means SETTLING. Cold loads are unchanged.
- `content/youtube.ts`: while SETTLING, and only when an unknown channel would block, hold media
  silently with no overlay. Resume on release when the verdict is allow or the pause is completed.
  Never resume on bail.
- `content/overlay.ts`: `MediaHold.release({ resume })`.
- `core/youtubeAccess.ts` (NEW, the ONE pattern list): whitelist carve-out paths and creator paths, as
  full regexFilters. `background/dnr.ts` compiles them; the content script tests the same strings for
  its in-SPA page gate. `/c/` and `/user/` are allowed wholesale and verified in-page from the
  canonical channel id, the way `/watch` is.
- Channel-page enrichment: a visited allowed channel page teaches its missing axis (canonical id plus
  vanity handle), through the same CHANNEL_OBSERVED path.
- SiteConfig gains `tempAllowedUntil` (an epoch, not a boolean, so expiry is not stale).

## As built (2026-10-23)

- **The in-SPA page gate hands off to the network instead of drawing its own wall.**
  `youtubePageAccess(url)` answers open / creator / carved / verify / blocked from the same
  strings DNR compiles. `decidePageGate` (pure) turns that into an action:
  - blocked, in-SPA, with config fetched FOR THIS URL: `location.replace(url)`, so DNR
    redirects to the block page with all of its context. This happens once per URL.
  - blocked on a FULL load (DNR let it through): a one-shot console canary, never a reload,
    since a reload could loop.
  - verify (`/c/`, `/user/`): on a full load, check the page's own channel metadata against
    the list and refuse with `replace(blockPageUrl)`. In-SPA, the inline data is stale, so
    reload and let the full-load path decide.
  - config not current yet: hold media and wait.
- **Settle hold, not settle overlay.** While the owner is not known yet, the video is held
  silently. That covers a null detection in the window, or a flexy `video-id` that differs from the
  URL's. On real YouTube the flexy video-id and the byline arrive together, about 1.6-2.3s after the
  URL changes. **Tradeoff:** an allowed video starts about 2s late on an SPA hop.
- **The hold resumes only in place.** Resume happens when the gate clears on the URL the hold was
  asserted for, or when the Delay/Breathing pause completes. It never happens when the user leaves.
  Every pass that keeps the hold re-asserts it (`repause`), which also records players YouTube
  swapped in already running.
- `core/blockPage.ts`: ONE builder for the block-page URL (DNR substitution, open-tab redirect,
  content-script refusal).

## Creator mode (schema v5)

- `YoutubeFeatureSettings.creatorMode: boolean` (default false) and `myChannel: ChannelEntry | null`.
- On: `studio.youtube.com` (explicit host filter, not the subdomain wildcard), the www/m routes that
  301 to Studio (`/upload`, `/live_dashboard`, `/my_live_events`, `/livestreaming`, `/dashboard`,
  `/analytics`, `/my_videos`, `/view_all_playlists`, `/features`, `/audiolibrary`,
  `/copyright_complaint_form`), and the `/signin` post-login hop. Each redirect target is re-checked
  by DNR, so the redirectors cannot leak. They stay allowed when the budget is spent. Studio time is
  not counted, and a budget crossing does not yank a Studio tab.
- `myChannel` acts as an implicit whitelist entry in the same `decideWatchGate` path and the same
  allow filters.
- Strict Mode: turning it on, or changing `myChannel` while it is on, is a weakening. Off is free.
- UI: a checkbox in the YouTube channel section, plus an optional "Your channel" input that goes
  through `parseChannelInput`.

## Not doing (justified)

- Search and subscriptions stay blocked (block page with channel links) rather than filtered. The
  feed filter is deliberately fail-open on unidentifiable cards, and a whitelist is default-deny, so a
  filtered search would leak every card it could not identify.
- Shorts by an allowed channel stay blocked under a site-level whitelist (unchanged; follow-up).
