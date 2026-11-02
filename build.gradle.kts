buildscript {
    dependencies {
        // Paparazzi 2.0.0-alpha02 (docs/screenshot-tests.md) depends on kotlin-bom 2.1.21. On this
        // classpath that bom would lift the Kotlin compiler artifacts past the Kotlin Gradle plugin
        // they belong to, and compileDebugKotlin dies with a NoSuchMethodError. The plugin runs on
        // the Kotlin that Gradle and KGP already put here, so it brings none of its own: the Kotlin
        // artifacts on the build classpath stay exactly what the plugins below resolve.
        classpath("app.cash.paparazzi:paparazzi-gradle-plugin:2.0.0-alpha02") {
            exclude(group = "org.jetbrains.kotlin")
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
