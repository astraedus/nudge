/**
 * `:qa-driver`: Nudge's device-QA driver, as typed instrumented tests.
 *
 * WHY A SEPARATE LIBRARY MODULE, AND NOT `app/src/androidTest`:
 * `am instrument` force-restarts the instrumentation's TARGET package. An `app/src/androidTest`
 * APK targets Nudge, so every run would kill Nudge's process, and a killed Nudge leaves its
 * accessibility service UNBOUND until the grant is re-applied (docs/TESTING.md, "device facts").
 * A library module's androidTest APK instruments ITSELF (targetPackage == its own package), so
 * Nudge is never the instrumented process: the test drives the phone, and Nudge runs the way a
 * user has it. The library's main source set holds only pure text parsers (`DeviceText`), so
 * `src/test` can pin them on the JVM; the device code is all androidTest.
 *
 * WHY THE TESTS DON'T SUPPRESS NUDGE: every `UiDevice` is created after
 * `Configurator.setUiAutomationFlags(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)` (see `QaDevice`).
 * Without that flag Android suppresses every other accessibility service while a UiAutomation
 * session is connected, which is why Maestro could never test this app.
 *
 * The test APK is debug-signed and never needs Nudge's key: it does not instrument Nudge, and it
 * reaches Nudge only through shell commands (`UiAutomation.executeShellCommand` runs as the shell
 * uid) and the DUMP-guarded qa control receiver of the `qa` build.
 *
 * Kept out of every release path: nothing here is a dependency of `:app`, and for this module a
 * release build is an empty AAR nobody consumes.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.astraedus.qadriver"
    compileSdk = 36

    defaultConfig {
        // Nudge's own minSdk: the driver must run on every API level Nudge claims to.
        minSdk = 26
        // Deliberately NOT under dev.astraedus.nudge: nothing in Nudge may ever mistake the
        // driver for itself (own-package checks, the never-counted list, Strict Mode's guard).
        testApplicationId = "dev.astraedus.qadriver"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("androidTest") {
            // The app's UiTags, compiled in rather than retyped: the driver finds controls by the
            // same constants the app tags them with. That directory holds only UiTags.kt, which
            // has no Android or Compose imports (its KDoc says why).
            java.srcDir("../app/src/main/java/com/astraedus/nudge/ui/testtags")
            // The ONE bench fixture, shared with scripts/device-qa.sh, so the two harnesses can
            // never seed different rules. QaControl.seedFixture reads it as the `rules.json` asset.
            assets.srcDir("../.maestro/fixtures")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // 2.3.0, not 2.4.x: 2.4 is a Kotlin rewrite that pulls a newer kotlin-stdlib than this
    // project's Kotlin 2.0.21. `Configurator.setUiAutomationFlags` exists since 2.2.0, and
    // `UiDevice.getUiAutomation()` re-reads the flags on every call (checked in the 2.3.0 source).
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
