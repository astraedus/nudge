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
- Plugin: `app.cash.paparazzi` **2.0.0-alpha02**, on the root buildscript classpath
  (`build.gradle.kts`). It is the only release that both renders on compileSdk 36 and runs on our
  toolchain (Java 17, Kotlin 2.0.21, AGP 8.13):
  - 1.3.5, the last stable, bundles API 34 layoutlib, which crashes on compileSdk 36 before it
    draws a frame (cashapp/paparazzi#1877: no `Build.VERSION_CODES_FULL`). Forcing its layoutlib
    to the API 36 build (15.2.3) does not help: 1.3.5's bridge hardcodes `icudt72l.dat` and the
    API 36 layoutlib ships `icudt75l.dat`.
  - alpha03 and later need Java 21 and Kotlin 2.3 (their Gradle module metadata says so).
  - alpha02 renders on API 36 (layoutlib 15.2.3), but depends on kotlin-bom 2.1.21. On the build
    classpath that lifts the Kotlin compiler artifacts past our Kotlin Gradle plugin and
    `compileDebugKotlin` dies with a `NoSuchMethodError`, so the classpath entry excludes
    `org.jetbrains.kotlin`: the build classpath's Kotlin is exactly what it is without Paparazzi.
  - On the unit-test classpath its runtime asks for kotlin-stdlib 2.1 and coroutines 1.10.
    `app/build.gradle.kts` resolves both unit-test classpaths consistently with the app's own
    classpaths, so every JVM test still runs on the library versions the app ships.

  None of this touches what ships: `releaseRuntimeClasspath` is identical with and without the
  plugin. Re-check the version before any compileSdk, AGP, Java or Kotlin bump; the Kotlin 2.3
  bump is when to move to the current 2.0.0 release and drop the exclude.

  Re-check this pair before any compileSdk, AGP or Kotlin bump.

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
