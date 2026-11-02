plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("app.cash.paparazzi")
}

import java.util.Properties
import java.io.FileInputStream

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.astraedus.nudge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.astraedus.nudge"
        minSdk = 26
        targetSdk = 36
        versionCode = 71
        versionName = "1.26.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "../nudge-release.keystore"))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "nudge")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            // R8 on (F-Droid review, MR 38398): shrink + optimise. Obfuscation is OFF in
            // proguard-rules.pro (-dontobfuscate): see the WHY there. shrinkResources stays off,
            // the smallest change that answers the review.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        /**
         * The device-QA build: the RELEASE build (R8 on, `-dontobfuscate`, not debuggable, so the
         * minified code paths and the "is this our own window" class-name check are what QA
         * exercises), plus the DUMP-guarded control receiver in `src/qa` that the `:qa-driver`
         * tests seed and read state through. Debug-signed, because it is bench software: it never
         * leaves a test device, and a release-key qa APK would be one more artefact that could.
         *
         * No `applicationIdSuffix`: it would put the qa build next to the release one on the bench,
         * and then the a11y component, the deep links and every `dev.astraedus.nudge` the logcat
         * oracle greps for would differ from what ships. Installing qa over release is a key switch
         * (scripts/qa-driver.sh handles it the way device-qa.sh does); see docs/TESTING.md.
         *
         * Never in a release path: CI builds `assembleRelease`/`bundleRelease`, and the qa
         * receiver is kept out of those by `verify<Variant>NoQaControl` and `QaControlManifestTest`.
         */
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // Library modules publish debug/release only; resolve them as release, like the
            // build type this one copies.
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // F-Droid rejects an APK that carries Google's encrypted "Dependency metadata" signing block.
    // Keep it in the Play bundle, drop it from the APK we publish on GitHub.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * Lint is a CI gate, not a suggestion.
     *
     * It is the ONLY check in this project that can see an API-level mistake. `minSdk` is 26, so
     * calling a method added in API 28 or 29 is a `NoSuchMethodError` on a real Android 8 or 9
     * phone — and no JVM test can see that (there is no Android runtime) and the bench Pixel 3
     * cannot reproduce it (it is API 31). Three such calls shipped undetected, one of them on the
     * accessibility hot path where it would have killed blocking outright on those devices.
     *
     * `abortOnError` so `NewApi` fails the build. `warningsAsErrors` stays FALSE deliberately: the
     * point is to gate the class of defect that is invisible everywhere else, not to make an
     * unrelated deprecation warning block a release at 2am. No baseline file — a baseline for
     * correctness errors is just a list of bugs nobody will read again.
     */
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager — the protection watchdog's 15-minute periodic check. Deliberately not
    // AlarmManager: an exact alarm needs SCHEDULE_EXACT_ALARM (a Play-review surface) and is
    // rate-limited on Android 14+, for a check whose tolerance is a quarter of an hour.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Glance, home-screen widgets, written in Compose. 1.2.0 is the latest STABLE line
    // (1.3.0-alpha exists; an alpha does not belong in a shipped widget). Needs compileSdk >= 35
    // and the Compose compiler plugin, both already in place above.
    //
    // Deliberately NOT `glance-appwidget-testing`: it is Robolectric-backed and Robolectric is
    // not on this project's classpath. Widget composables stay dumb and the PURE mappers behind
    // them (WidgetSnapshotMapper, WidgetDeepLink, WidgetRefreshDebouncer) carry the tests.
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    // QR / barcode scanning and generation (ui/qr/, docs/architecture/qr.md). FOSS only: Nudge
    // ships on F-Droid and IzzyOnDroid, so ML Kit and the Play-services code scanner are out.
    //  - zxing core (Apache-2.0): pure-Java encode + decode. The decode runs in OUR code over the
    //    camera's luminance plane, which is what makes it JVM-testable.
    //  - CameraX (Apache-2.0, AOSP Jetpack, no Play services): Camera2 under a lifecycle-bound API,
    //    torch control, and PreviewView. Chosen over zxing-android-embedded, which drives the
    //    deprecated Camera1 API, ships its own View-based landscape capture activity and is in
    //    maintenance mode. 1.5.3 is built against kotlin-stdlib 2.0.21, this project's Kotlin;
    //    1.6.x pulls kotlin-stdlib 2.1.20, so it waits for the Kotlin bump.
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.json:json:20231013")
    testImplementation("io.mockk:mockk:1.13.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

/**
 * The JVM unit tests run against the library versions the app ships.
 *
 * Paparazzi (docs/screenshot-tests.md) adds its runtime to every unit-test classpath, and that
 * runtime asks for newer kotlin-stdlib and kotlinx-coroutines than the app is built with. Left to
 * Gradle's "highest wins", the whole suite would quietly run on coroutines-test 1.10 while the app
 * ships 1.9, which is the library #53's leak detection leans on. Consistent resolution takes the
 * app's resolved version for every module the two classpaths share; modules only Paparazzi uses
 * resolve as normal, except Kotlin's own artifacts (kotlin-reflect, which mockk and Paparazzi
 * load), held at the Kotlin this build compiles with so they match the stdlib beside them.
 */
afterEvaluate {
    val kotlinVersion = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    for (variant in listOf("debug", "release")) {
        for (kind in listOf("Compile", "Runtime")) {
            configurations.getByName("${variant}UnitTest${kind}Classpath").apply {
                shouldResolveConsistentlyWith(configurations.getByName("${variant}${kind}Classpath"))
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") useVersion(kotlinVersion)
                }
            }
        }
    }
}

/**
 * Paparazzi's screenshot tests and the rest of the JVM suite never share a test JVM.
 *
 * Paparazzi renders with layoutlib, whose jar is the real Android framework (native calls and all)
 * rather than the android.jar stubs. On one classpath, every test that mocks a framework class
 * (PackageManager, AccessibilityNodeInfo, WindowManager) runs that class's real static initialiser,
 * which dies in `SystemProperties.native_get`; and a framework class that failed to initialise
 * stays broken for the gallery tests that run later in the same fork. 83 tests failed that way.
 *
 * So a run that includes a Paparazzi task (`verifyPaparazziDebug`, `recordPaparazziDebug`) runs the
 * screenshot gallery and nothing else, and every other run leaves the gallery out and takes
 * layoutlib off the classpath, so the suite sees exactly the classpath it had before Paparazzi.
 * Run the two in separate Gradle invocations, as CI does (ci.yml, screenshots.yml).
 */
val screenshotPackage = "com.astraedus.nudge.ui.gallery"
gradle.taskGraph.whenReady {
    // Not any "Paparazzi" task: testDebugUnitTest itself depends on its resources task.
    val paparazziRun = allTasks.any {
        it.project == project && it.name.matches(Regex("(record|verify|cleanRecord)Paparazzi.*"))
    }
    tasks.withType<Test>().forEach { test ->
        if (paparazziRun) {
            test.filter.includeTestsMatching("$screenshotPackage.*")
        } else {
            test.filter.excludeTestsMatching("$screenshotPackage.*")
            test.classpath = test.classpath.filter { !it.name.matches(Regex("layoutlib-[0-9].*\\.jar")) }
        }
    }
}

/**
 * Unit-test JVM forks: make a process-global coroutine leak NAME ITSELF.
 *
 * Issue [#53](https://github.com/astraedus/nudge/issues/53) cost two investigations and shipped
 * twice because the CI log named the victim and threw the offender away.
 *
 *  - `exceptionFormat = FULL`: `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` attaches the
 *    exception that ACTUALLY leaked as a **suppressed** throwable. The default `SHORT` prints one
 *    line and drops it, which is precisely the information needed — so the one flag that turns "some
 *    unrelated test failed, re-run the job" into "here is the offender's file and line" is this one.
 *  - `STANDARD_ERROR`: Gradle attributes a fork's stderr to the test that was RUNNING when it was
 *    written, which is how `LeakProbeHandler` names the offender rather than the casualty. It costs
 *    a handful of JVM/agent warning lines per run and nothing else.
 *  - `FAILED` is listed explicitly because assigning `events` REPLACES Gradle's default set, and
 *    losing the per-test FAILED line would be a bad trade for the two above.
 *
 * `-Dnudge.leakprobe=1` on the Gradle command line is forwarded into the fork to arm the probe; see
 * `app/src/test/java/com/astraedus/nudge/LeakProbeHandler.kt`.
 */
tasks.withType<Test>().configureEach {
    testLogging {
        events = setOf(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
    providers.systemProperty("nudge.leakprobe").orNull?.let {
        systemProperty("nudge.leakprobe", it)
    }
}

/**
 * The MERGED manifest's permission set is an allowlist, checked on every assemble/bundle.
 *
 * Nudge's listing promises no INTERNET permission, and the manifest we write is not the manifest
 * that ships: every library contributes its own `<uses-permission>` at merge time, silently.
 * (WorkManager already adds WAKE_LOCK and ACCESS_NETWORK_STATE; androidx.core adds its
 * dynamic-receiver permission.) So a dependency bump could add INTERNET and no source file, review
 * or JVM test would show it. This reads what actually ships and fails the build on anything not
 * listed here.
 *
 * Adding a permission on purpose = adding it here, in the same diff, with the reason next to it.
 */
val allowedMergedPermissions = setOf(
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.CAMERA", // QR / barcode scanner, requested at runtime (ui/qr/)
    "android.permission.WAKE_LOCK", // WorkManager
    "android.permission.ACCESS_NETWORK_STATE", // WorkManager constraint tracking; not network access
    "dev.astraedus.nudge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" // androidx.core, app-private
)

abstract class VerifyMergedPermissionsTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name="([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }.toSortedSet()
        val unexpected = declared - allowed.get()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Merged manifest declares permissions outside the allowlist: $unexpected. " +
                    "Either the app manifest declares them or a library added them at merge time. Strip a library one with " +
                    "<uses-permission android:name=\"...\" tools:node=\"remove\"/> in AndroidManifest.xml, " +
                    "or, if it is intended, add it to allowedMergedPermissions in app/build.gradle.kts."
            )
        }
        report.get().asFile.writeText(declared.joinToString("\n", postfix = "\n"))
    }
}

/**
 * The qa control receiver must never reach a non-qa APK.
 *
 * It lives in `src/qa`, which AGP merges into the qa build type only, so today this can only fail
 * if someone copies the declaration into `src/main` or `src/debug`. That is exactly the edit nobody
 * reviews closely ("just make the receiver reachable on debug too"), and the cost would be an
 * exported component that wipes a user's rules. Same shape as [VerifyMergedPermissionsTask]: it
 * reads the manifest that SHIPS, so a library or an overlay cannot sneak past it either.
 */
abstract class VerifyNoQaControlTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val leaks = QA_CONTROL_MARKERS.filter { text.contains(it) }
        if (leaks.isNotEmpty()) {
            throw GradleException(
                "A non-qa merged manifest names the qa control channel ($leaks). It belongs in " +
                    "app/src/qa/AndroidManifest.xml only (see QaControlReceiver)."
            )
        }
        report.get().asFile.writeText("clean\n")
    }

    companion object {
        /** The receiver's class and its action, as they appear in a merged manifest. */
        val QA_CONTROL_MARKERS = listOf("com.astraedus.nudge.qa.", "dev.astraedus.nudge.qa.")
    }
}

androidComponents {
    // The qa variant runs no JVM tests of its own: `./gradlew test` (CI) would otherwise run the
    // whole suite a THIRD time against identical main code. Its one extra source file is covered by
    // QaControlManifestTest (placement + guard) and by the :qa-driver suite on a device.
    beforeVariants(selector().withBuildType("qa")) { it.enableUnitTest = false }

    onVariants(selector().withBuildType("release")) { variant ->
        // QaControlManifestTest reads the RELEASE merged manifest from every unit-test fork, so
        // the gate runs on `./gradlew test`, which is what CI runs before anything ships.
        val merged = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach {
            inputs.file(merged).withPropertyName("releaseMergedManifest")
                .withPathSensitivity(PathSensitivity.NONE)
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Dnudge.releaseMergedManifest=${merged.get().asFile.absolutePath}")
            })
        }
    }

    onVariants(selector().withBuildType("qa")) { variant ->
        // The positive control for the test above: the same detection must FIND the receiver in
        // the qa manifest, or a "release is clean" pass could be a matcher that matches nothing.
        val merged = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach {
            inputs.file(merged).withPropertyName("qaMergedManifest")
                .withPathSensitivity(PathSensitivity.NONE)
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Dnudge.qaMergedManifest=${merged.get().asFile.absolutePath}")
            })
        }
    }

    onVariants { variant ->
        if (variant.buildType == "qa") return@onVariants
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val verifyNoQa = tasks.register<VerifyNoQaControlTask>("verify${suffix}NoQaControl") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/no-qa-control/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$suffix" || it.name == "bundle$suffix" }
            .configureEach { dependsOn(verifyNoQa) }
    }

    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyMergedPermissionsTask>("verify${suffix}MergedPermissions") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            allowed.set(allowedMergedPermissions)
            report.set(layout.buildDirectory.file("reports/merged-permissions/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$suffix" || it.name == "bundle$suffix" }
            .configureEach { dependsOn(verify) }
    }
}
