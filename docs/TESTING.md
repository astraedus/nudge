# Testing philosophy and coverage targets

> **WHICH LAYER a test belongs at, and the rules a bug fix owes, live in `docs/testing-strategy.md`.**
> This file is the philosophy and the per-layer limitations; that one is the routing table. Read both.

The full version of the rule summarised in `CLAUDE.md`. **Read when deciding what a change owes in tests.**

## Testing Philosophy — Never Regress

**Every new feature MUST ship with tests that cover its core behavior.** The test suite is the safety net that lets us move fast without breaking existing functionality.

Principles:
- **Tests are not optional.** If you add a feature, you add tests. No exceptions.
- **Test the contract, not the implementation.** Domain logic (BlockEngine, use cases, StatsCalculator) gets unit tests. UI gets integration tests for navigation/state.
- **CI runs the full suite on every PR (`ci.yml`); locally run only the scoped tests you touched** (`--tests '<pattern>' --max-workers=2`). Never the full suite on the laptop. If CI fails, the feature isn't done.
- **Regression = bug.** If a new feature breaks existing behavior, that's a blocker — fix it before merging.
- **Domain layer is the priority.** Pure Kotlin with no Android deps = fast JVM tests. Test BlockEngine decisions, schedule evaluation, counter logic, export/import round-trips.
- **When fixing a bug, write a test that reproduces it first.** Then fix. The test proves the fix works and prevents re-introduction.

Test locations:
- `app/src/test/` — JVM unit tests (domain, data, use cases)
- `app/src/test/resources/a11y-captures/` — **real device event streams, replayed as fixtures.** See
  `docs/architecture/accessibility-event-pipeline.md`. The workflow for a report about the
  accessibility service is: reproduce on a device with `scripts/a11y-capture.sh` running, commit the
  `.jsonl`, write the assertion that FAILS on it, then fix. Two rules make this worth having: a
  capture must state its own expected count in its `#` header (a fixture with no oracle cannot fail,
  and `A11yCaptureReplayTest` asserts every capture has one), and each fixture assertion is paired
  with a counterfactual that runs the OLD rule over the same capture and asserts it still fails —
  otherwise a capture that quietly stops reproducing leaves a green test asserting nothing.
- `app/src/androidTest/` — instrumented tests (Room migrations, accessibility service behavior)

One tool this module deliberately does not use, and two things it cannot JVM-test, so nobody
re-discovers any of them:
- **There is no Robolectric here — evaluated for exactly this bug class, and declined.** It is the
  textbook answer to lifecycle-ordering bugs, and it was priced against them: a per-SDK `android-all`
  runtime download, and a permanent cost on every `./gradlew test` run, to reach decisions that can be
  reached with no dependency at all. What replaces it: the lifecycle DECISIONS are extracted into the
  pure `domain/block/OverlayLifecycle` and driven as ordinary JVM tests against the real
  `BlockLaunchGuard` (`OverlayLifecycleTest`, `OverlayLifecycleGuardTest`); the accessibility-event
  side is already pure (`AccessibilityEventRecord` at the service boundary) and replayed from recorded
  `.jsonl` captures. What is still genuinely untestable is a narrow residue: a JVM test cannot
  construct an `AccessibilityEvent` or an `AccessibilityNodeInfo` (MockK can stub the latter), which is
  exactly why the event pipeline converts to a pure record at the boundary and the untestable part is
  one field-copying function. The honest residue of the new arrangement is that nothing at this layer
  can prove the Activity ADAPTER actually calls the decision class on every callback — that half stays
  a source-level contract test, and it is the standing argument for an emulator layer later.
- Consequently the node-tree label path (`InteractionHandler.resolveLabelIfUnknown` ->
  `InAppDetector.detectFeature`) has no JVM coverage. It affects the counter's LABEL only, never its
  number, which is the reason that split is drawn where it is.

**JVM tests cannot see API-level errors; lint can.** This is the third thing the module cannot test,
and unlike the two above it is not a documented limitation, it is a gate. `minSdk` is 26, so a call to
a method added in API 28 or 29 compiles cleanly, passes every unit test (there is no Android runtime
to object), and works perfectly on the bench Pixel 3 — which is API 31. On a real Android 8 or 9 phone
it is a `NoSuchMethodError` at the call site.

Three of them reached `main` before anyone looked, found only by running `./gradlew lintDebug` by hand
during unrelated work:

| Call | Added in | What it did on API 26-28 |
|---|---|---|
| `AccessibilityEvent.getScrollDeltaX/Y()` | 28 | inside `toRecord()`, which runs for **every** accessibility event — the service died on the first one, so blocking never worked at all |
| `AppOpsManager.unsafeCheckOpNoThrow()` ×2 | 29 | the Home dashboard's screen-time read, and Settings' usage-access row |

The AppOps pair were byte-identical copies of each other, which is the second lesson: the duplication
is what made it two bugs instead of one. They are now one `util/UsageAccess.kt` behind one SDK check.

So `lintDebug` runs in CI (`.github/workflows/release.yml`, gating both the tag release and the rolling
`main-latest` build) with `lint { abortOnError = true; warningsAsErrors = false }` — `NewApi` fails the
build, an unrelated deprecation warning does not. **No `lint-baseline.xml` for errors**: a baseline for
correctness errors is a list of bugs nobody will read again. The gate was mutation-checked when it was
added — deleting the `SDK_INT` guard in `UsageAccess.kt` fails the build with the exact `NewApi` error,
so it is a gate and not a green tick.

What is still NOT covered: nothing has ever run this app on an API 26-27 device. Lint proves we do not
*call* a missing method; it cannot prove the app is usable on Android 8. That wants an emulator run.

**Source-level contract tests** (`*ContractTest`) read a `.kt` file as text and assert on the SHAPE
of the code — where an early return sits, that a call happens before a branch. They exist because
this repo's worst bugs have been ordering bugs, which no value-level test can see. Two rules for
writing one: strip comments before grepping (a comment that quotes the code it explains will
otherwise be read as code, in either direction), and assert the invariant rather than the spelling,
so a faithful refactor re-pins cleanly instead of being deleted.

Coverage targets (aspirational, enforce on new code):
- Domain layer: >90% line coverage
- Data layer (repositories, DAOs): >70%
- UI ViewModels: key state transitions tested

## L6 — Release-gate device QA is `scripts/device-qa.sh all`

Every release up to v1.18.3 was device-verified by an LLM (`device-tester`) walking the bench
Pixel from a prose brief: **60–100 minutes a run, four runs in September alone**, and a
different walk every time. The recurring checks are now a script.

```bash
scripts/device-qa.sh all          # the gate (~18 min); nonzero exit on any FAIL
scripts/device-qa.sh group1 && APK=installed scripts/device-qa.sh group2 \
  && APK=installed scripts/device-qa.sh group3   # the same gate in three chunks, each under 10 min
scripts/device-qa.sh delay-block  # one case, while iterating
scripts/device-qa.sh list
APK=installed scripts/device-qa.sh all   # validate the build already on the bench
APK=main ALLOW_CERT_SWITCH=1 scripts/device-qa.sh setup   # put the release key back on the bench
```

**The bench carries the RELEASE signing key.** Before installing, the script compares the installed
APK's signer with the incoming one (`scripts/cert-guard.sh`) and refuses, touching nothing, if they
differ: Android cannot update across keys, so the only way through is an uninstall that wipes the
bench, and a silent `APK=debug` switch on 2026-09-29 broke every later CI install for the next lane.
`ALLOW_CERT_SWITCH=1` opts in (uninstall, install, loud "bench state was wiped"); a run that leaves
a debug build installed prints the restore command on exit. Tests: `scripts/test-cert-guard.sh`
(device-free, shimmed `adb`/`apksigner`, runs in CI).

It resolves the ADB serial, takes the shared Pixel lock, installs the APK under test
(`APK=main` | `release` | `installed` | `debug` | a path), verifies the `versionCode`, applies
the accessibility / overlay / usage grants at OS level, pins the screen, runs the cases, copies
every screenshot to `~/Pictures/screenshots/nudge/qa-<ts>/`, prints a PASS/FAIL table with
per-case durations, and restores every setting it touched on any exit path.

**The cases**: `setup` (first-run onboarding + the two fixture rules) · `delay-block` ·
`home-reopen` ([#58](https://github.com/astraedus/nudge/issues/58), 5 trials) ·
`walkaway-count` · `daily-limit-refresh` ([#50](https://github.com/astraedus/nudge/issues/50)) ·
`notif-idle` ([#63](https://github.com/astraedus/nudge/issues/63)) · `crash-check` ·
`refusal-alert` ([#62](https://github.com/astraedus/nudge/issues/62)).

### Why it is not a Maestro suite, and must not become one

Maestro — and every other UiAutomator-based driver — connects a `UiAutomation` session, and
**Android suppresses all other accessibility services while one is connected** unless the client
passes `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, which Maestro does not. Nudge *is* an
accessibility service. Measured on the bench:

| | `Bound services` | YouTube launch | `BlockEngine` lines |
|---|---|---|---|
| during a Maestro session | `{}` | opens instantly | 0 |
| after it exits | populated | blocked for 5s | 6 |

So a Maestro flow **disables the feature under test**, and does it silently — it reads as a
flaky assertion, which is very likely why this harness did not already exist. Blocking behaviour
is therefore driven with plain ADB and asserted against two non-invasive oracles: the foreground
activity (`dumpsys activity activities`) and the service's own decision log
(`block package=… reason=delay_rule delaySeconds=5`). That log line is *stronger* evidence than
an on-screen string — it names the rule that fired.

**But a decision is not a block.** The overlay launch can still be refused after the verdict
with `DROP_FOREGROUND_MOVED`, leaving the user in the app unblocked under a healthy-looking log
— the ambiguity that cost the v1.12.0 cycle. So every block assertion checks both halves: the
decision in the log, and the overlay actually reaching the screen.

Reading the screen is safe; holding a driver session open is not. A bare `uiautomator dump` was
measured across repeated calls with the service staying bound and a block on screen completing
normally, so it is used to read on-screen copy and to locate targets at runtime — coordinates
are derived from the matched node's bounds, never fixed geometry.

`.maestro/nudge-setup.yaml` is the one Maestro flow kept: it walks onboarding, turns on debug
logging, and imports `.maestro/fixtures/rules.json` — Nudge's own UI, where nothing is blocked.

### Four device facts the script encodes (and you will otherwise rediscover)

- **A completed countdown grants passthrough, and it survives a force-stop.** Re-opening the app
  logs `skip evaluation … reason=passthrough` and opens it free. Only leaving to the launcher
  revokes it — which is [#58](https://github.com/astraedus/nudge/issues/58)'s whole subject. So
  every block-expecting case starts from Home, and a case that does not is asserting on its
  predecessor's leftovers.
- **Force-stopping `dev.astraedus.nudge` leaves the accessibility service unbound** and Android
  does not rebind it. Nothing in the harness may force-stop Nudge; blocking stays dead until the
  grant is re-applied.
- **`pm clear` deletes `enabled_accessibility_services`**, and a write straight afterwards binds
  the service only for it to be pruned again on the app's first launch (the write raced
  `AccessibilityManagerService`'s installed-services refresh). The grant is re-applied *after*
  setup and re-checked as a precondition of every case.
- **Debug logging is a DataStore preference, wiped by `pm clear`.** On a release build
  `NudgeLogger` emits nothing without it (`BuildConfig.DEBUG || the preference`), and the log
  oracle goes silent. The setup flow re-arms it through Settings → Version ×7 → Debug Logging.
- **Log tags are literals, never inferred (#120).** R8 merges classes, so a tag read off the call
  stack came out as `ResultKt` / `TuplesKt` on release. Every logging class takes
  `logger.tagged("<Name>")` once (`NudgeA11yService`, `NudgeMonitor`, `BlockOverlay`,
  `TabCoverOverlay`, `CounterOverlay`, `BlockEngine`, ...); `Nudge` is the fixed fallback; the
  fixed-tag lines `NudgeA11yTrace` (`scripts/a11y-capture.sh`) and `ProtectionWatchdog` keep their
  own. Filter a release log with `adb logcat -s NudgeA11yService:* BlockOverlay:*`. A new logging
  class adds its own `tagged()` literal; `NudgeLoggerTagTest` pins the mechanism.
- **A daily limit is enforced on RE-ENTRY, not mid-session** — unless the rule also has a
  time-based auto-kick or the time-remaining overlay. The 30-second foreground clock that would
  otherwise notice a budget running out while you sit in the app is gated on
  `autoKickAfterMinutes != null || (showTimeRemaining && dailyLimitMinutes != null)`
  (`CounterCacheRefresher.needsForegroundTimeTick`). Measured: 150s sitting in Calculator on a
  plain 1-minute limit produced exactly ONE evaluation, at t=0, `dailyUsageMs=93`. So
  `daily-limit-refresh` burns the budget, leaves, and comes back — which is
  [#50](https://github.com/astraedus/nudge/issues/50)'s own scenario and needs no optional
  feature switched on. Worth knowing before filing "the limit didn't stop me" as a bug.

### What it does not cover

`refusal-alert` needs the debug-only `WatchdogDebugReceiver` (`app/src/debug/`), absent from any
release APK; on a release build the case reports **SKIP** and `APK=debug` is the mode that
exercises it. Exploratory judgement — does this screen *look* right, is this copy good — remains
`device-tester`'s job, working from the screenshots the script dumps. This script covers the
recurring checks; it does not replace taste.

## L6b — The instrumented driver: `:qa-driver` + the `qa` build type

`device-qa.sh` drives Nudge blind (ADB taps at coordinates read off `uiautomator dump`) because a
driver session used to switch Nudge off. That constraint has an exit: a `UiAutomation` client that
passes **`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`** leaves every other service bound. The
`:qa-driver` module is the same cases as typed Kotlin instrumented tests with real selectors, built
on that flag.

```bash
ALLOW_CERT_SWITCH=1 scripts/qa-driver.sh            # delay-block walkaway-count home-reopen notif-idle
ALLOW_CERT_SWITCH=1 scripts/qa-driver.sh spike spike-control   # re-prove the flag (and its control)
scripts/qa-driver.sh list
APK=main ALLOW_CERT_SWITCH=1 scripts/device-qa.sh setup        # put the release build back afterwards
```

**The proof (2026-11-02, Pixel 3, API 31, `SuppressionSpikeTest`).** Flag ON: `Bound services`
populated in 27 of 27 host samples taken while the test held its `UiAutomation`, the YouTube launch
logged `block package=com.google.android.youtube reason=delay_rule delaySeconds=5`, the overlay
reached the screen, and Nudge's process (same pid) never lost its connection. Flag OFF (the control,
`-e qa.suppress true`): `{}` in 52 of 60 samples, no decision, no overlay (the Maestro result,
reproduced), and Nudge rebound only after the test released the session. The runner keeps both
cases so the proof re-runs whenever the bench gets a new Android version.

The flag must be set before the FIRST `UiDevice.getInstance` (`QaDevice` does it in one place, and
`NudgeCase` asserts it on every test). A test that builds its own `UiDevice` first, or a library that
connects its own `UiAutomation` (Espresso's, Compose's test rule), silently brings the suppression
back. The base class also fails with a `HARNESS:` message if Nudge is not bound within 10 seconds, so
"the driver switched Nudge off" can never read as "Nudge did not block".

**Why a library module, not `app/src/androidTest`.** `am instrument` force-restarts the
instrumentation's target package, and a force-stopped Nudge stays unbound (the device fact above).
A library module's test APK instruments itself (`dev.astraedus.qadriver`), so Nudge runs the way a
user has it and the driver reaches it only through shell commands.

**The `qa` build type** (`initWith(release)`: R8 on, the same code users run, but debug-signed)
adds one thing in `app/src/qa/`: `QaControlReceiver`, which replaces `pm clear` + the onboarding
walk with three commands: `reset` (every table, the in-memory passes, Strict Mode and Nuke off, onboarding done, debug logging on), `seed-rules` (base64 of
`.maestro/fixtures/rules.json`, the one fixture both harnesses share) and `state` (JSON: rule
count, the a11y connection, debug logging, the walk-away counters).

```bash
adb shell am broadcast -n dev.astraedus.nudge/com.astraedus.nudge.qa.QaControlReceiver \
  -a dev.astraedus.nudge.qa.CMD --es cmd state
```

Its rules are merge gates, and `QaControlManifestTest` pins them on the JVM:
- **Only in the qa source set.** No main, debug or release manifest or source mentions it.
- **Exported only behind `android.permission.DUMP`**, which no installable app can hold
  (signature|privileged|development): only `adb shell` can send it a command.
- **Allowlisted and bounded.** One action, three commands, a 32 KB cap on the seed; it never runs a
  shell, loads code or opens a path taken from an extra.
- **The release manifest's exported set is pinned**, component by component, and contains no
  DUMP-guarded component of ours. (Two androidx library receivers in release are already
  DUMP-guarded: WorkManager's `DiagnosticsReceiver` and `ProfileInstallReceiver`. The test names
  them, so a third one cannot hide behind them.)
- **Never published, never release-signed.** `release.yml` builds `assembleRelease` /
  `bundleRelease` only; the qa APK uses the debug signing config, so Play would reject it, and
  installing it on the bench is a cert switch through `cert-guard.sh` like any `APK=debug` run.

CI compiles `:app:compileQaKotlin` and `:qa-driver:compileDebugAndroidTestKotlin` and runs the
driver's JVM parser tests (`DeviceTextTest`), so a broken driver shows up in the PR, not at the bench.

The guard was checked on the bench too (2026-11-02): `am broadcast` from the shell gets the `state`
JSON back. The same broadcast from an app uid (`run-as dev.astraedus.qadriver am broadcast --user 0
…`) is dropped by the system with `Permission Denial: … requires android.permission.DUMP`, and
the receiver never runs.

**First bench runs (2026-11-02, qa build of 1.26.0):** `delay-block` 20s, `walkaway-count` 40s,
`home-reopen` 121s (5/5 trials re-blocked), `notif-idle` 366s, all PASS. A case's `note(...)` lines
land in the result bundle (`INSTRUMENTATION_RESULT: qa.note.NN=…` in `<case>.instrument.txt`), not
only in logcat, which the waits clear. Two selector traps cost the first run:
- **The system navigation bar's button is also `By.desc("Back")`.** Scope any Back lookup to
  Nudge's package, or the driver presses its way out of the app.
- **A `clickable` container merges its children's semantics, and a merged child's `testTag` is
  dropped.** Put the tag on the clickable node itself (`UiTags.WEEK_CARD` is on the card, not on
  the summary `Text`).

**How the runner keeps a long case alive.** Each case is its own `am instrument`, started under
`nohup` on the device and writing to a file the host polls: `notif-idle` spends five minutes with
the screen off, and an `-w` session tied to a Wi-Fi adb connection does not reliably survive that.
The bench PIN stays out of this public repo, so the host unlocks before every case. Evidence lands in
`~/Pictures/screenshots/nudge/qa-driver/<run>/`: screenshots per beat, a logcat window per case and
the raw instrumentation output.

**Two shell traps the driver encodes.** `UiAutomation.executeShellCommand` splits on whitespace and
does no quoting, so an empty argument cannot be passed (revoking the grant is `settings delete`,
never `settings put … ''`) and base64 must be `NO_WRAP`. And each logcat wait clears the buffer
first, then waits for a fresh line: counting across a buffer that rotated mid-case invents misses.

`device-qa.sh all` stays the release gate until the driver has a run history of its own; the driver
does not yet port `setup`, `daily-limit-refresh`, `crash-check` or `refusal-alert`.
