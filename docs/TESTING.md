# Testing philosophy and coverage targets

> **WHICH LAYER a test belongs at, and the rules a bug fix owes, live in `docs/testing-strategy.md`.**
> This file is the philosophy and the per-layer limitations; that one is the routing table. Read both.

The full version of the rule summarised in `CLAUDE.md`. **Read when deciding what a change owes in tests.**

## Testing Philosophy — Never Regress

**Every new feature MUST ship with tests that cover its core behavior.** The test suite is the safety net that lets us move fast without breaking existing functionality.

Principles:
- **Tests are not optional.** If you add a feature, you add tests. No exceptions.
- **Test the contract, not the implementation.** Domain logic (BlockEngine, use cases, StatsCalculator) gets unit tests. UI gets integration tests for navigation/state.
- **Run `./gradlew test` before every commit.** If tests fail, the feature isn't done.
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
