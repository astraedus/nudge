buildscript {
    // Paparazzi's plugin imports kotlin-bom 2.1.21. Unchecked, that lifts the Kotlin compiler
    // artifacts on this classpath past the Kotlin Gradle plugin 2.0.21 they belong to, and
    // compileDebugKotlin dies with a NoSuchMethodError. Hold every Kotlin artifact at the pinned
    // toolchain version (docs/screenshot-tests.md).
    configurations.classpath {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("app.cash.paparazzi") version "2.0.0-alpha02" apply false
}
