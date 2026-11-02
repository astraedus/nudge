# Screenshot tests (Paparazzi)

Golden-image tests for a small gallery of screens. They render on the JVM through
[Paparazzi](https://github.com/cashapp/paparazzi) (Android's layoutlib), so there is no emulator,
no device and no Robolectric. Each test draws one screen from a fixed fixture state and compares
the frame pixel by pixel with a PNG committed to the repo. A layout change that moves anything,
like a switch losing its end padding, fails the comparison and produces a diff image.

- Tests: `app/src/test/java/com/astraedus/nudge/ui/gallery/ScreenGalleryTest.kt`
- Fixtures: `GalleryFixtures.kt` in the same package
- Goldens: `app/src/test/snapshots/images/`
- CI: `.github/workflows/screenshots.yml` (verifies on every PR and every push to main)
- Plugin: `app.cash.paparazzi` 2.0.0-alpha02, pinned in the root `build.gradle.kts`. Why this
  version: 1.3.5 (the last stable) crashes on compileSdk 36 (cashapp/paparazzi#1877: its API 34
  layoutlib has no `Build.VERSION_CODES_FULL`). alpha02 is the first release with API 36 layoutlib.
  alpha04 and later need Java 21 and Kotlin 2.3. Re-check this pin before any compileSdk, AGP or
  Kotlin bump.

## Add a screen

1. Give the screen a stateless entry point, `XContent(state, callbacks)`. The production
   `XScreen(viewModel)` collects state and calls it, and the gallery calls it with a fixture. Keep
   dialogs, sheets and effects in `XScreen`, so `XContent` is just the layout. The move must not
   change behaviour: the same composables in the same order, with view-model calls turned into
   lambdas.
2. Add a fixture to `GalleryFixtures`. Use fictional data, never anything copied off a real
   device. Pin every timestamp (`createdAt = 0`) and avoid anything that reads the clock, the
   timezone or `random()`. Where production computes a value (sections, copy), call the production
   function instead of restating its output.
3. Add a `@Test` to `ScreenGalleryTest` using `snapshot(dark = …) { XContent(…) }`. Test names
   become file names, so make them describe the state (`home_dark`, `manageApps_light`).
4. Record the goldens on CI (below) and commit them in the same PR.

## Record goldens (on CI only)

Goldens come from the CI image, never from a laptop. Fonts rasterise differently from machine to
machine, so a golden recorded locally can fail on CI with no UI change at all.

1. Push your branch.
2. Either add the **`record-screenshots`** label to the PR (`gh pr edit <n> --add-label
   record-screenshots`), or run Actions > Screenshots > Run workflow on your branch with
   **record** ticked (`gh workflow run screenshots.yml --ref <branch> -f record=true`). A
   label-triggered run ends red on purpose: it recorded, it did not verify.
3. Download the `paparazzi-goldens` artifact
   (`gh run download <run-id> -n paparazzi-goldens -D app/src/test/snapshots`), check the images
   look right, and commit them. Remove the label, so the next push verifies against them.

Locally, `./gradlew recordPaparazziDebug --tests 'com.astraedus.nudge.ui.gallery.*'` is fine for
looking at a render while you iterate. Don't commit what it writes.

## Review a diff

When `verifyPaparazziDebug` fails on CI, the run uploads `paparazzi-diffs`. For each failing
test, `app/build/paparazzi/failures/` holds `delta-<test>.png`: expected, diff and actual side by
side, with the changed pixels highlighted in the middle panel. Open it and decide:

- **Unintended change**: fix the code. The golden stays.
- **Intended change**: re-record on CI and commit the new goldens.

Locally: `./gradlew verifyPaparazziDebug --tests 'com.astraedus.nudge.ui.gallery.*'`, then open
`app/build/paparazzi/failures/`. Small anti-aliasing differences against CI goldens are expected
on a laptop, so judge by the diff image, not the pass/fail.

## Rule: a PR that changes goldens explains why

Any PR that adds, removes or changes a PNG under `app/src/test/snapshots/` says in its body which
screens changed and what the reviewer should see (for example "Home: the master switch moves 12dp
in from the edge"). A golden change with no explanation is treated as a regression until shown
otherwise. Reviewers open the changed PNGs in the PR's file view (GitHub shows image diffs) before
approving.
