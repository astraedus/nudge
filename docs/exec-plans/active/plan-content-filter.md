# Plan: Content Filter (generic restricted-website blocker)

## Goal
Block restricted websites across mobile browsers via the existing accessibility URL-bar reader, matching a bundled blocklist + keywords. User-facing framing is generic "Content Filter" only.

## Steps
- [ ] Make `WebDomainMatcher.normalizeToBaseDomain` accessible (or add a public base-domain extractor) for ContentFilterMatcher reuse
- [ ] `WebDomainDetector`: replace Chrome-only ids with per-package id map; multi-browser isBrowser/detectUrl
- [ ] `domain/ContentFilterMatcher.kt`: matchesDomain (base + parent strip), matchesKeyword, DEFAULT_KEYWORDS
- [ ] `data/repository/ContentFilterRepository.kt`: lazy bg-loaded Set<String>, suspend isBlocked()
- [ ] `NudgePreferences`: contentFilterEnabled (default false) + contentFilterMode (default HARD_BLOCK)
- [ ] `EvaluateBlockUseCase.evaluateWebDomain`: after per-rule check, consult content filter
- [ ] `SettingsScreen`: generic "Block restricted websites" toggle (NudgePreferences pattern)
- [ ] Tests: ContentFilterMatcherTest, WebDomainDetectorTest (mockk), use-case test
- [ ] Add mockk testImplementation
- [ ] gradle testDebugUnitTest + assembleDebug

## Decisions made
- ContentFilterRepository uses @Inject @Singleton constructor (Hilt auto-binds, like WebDomainDetector). No @Provides needed.
- Inject ContentFilterRepository behind an interface to keep EvaluateBlockUseCase testable without loading the 274k asset.
- HashSet<String> of 274k base domains (~4.4MB file). Acceptable heap on Pixel 3. Buffered reader load on Dispatchers.IO, guarded by Mutex.
- mockk added as testImplementation for AccessibilityNodeInfo mocking (no mockk currently present; spec requests it).
