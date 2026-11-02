pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nudge"
include(":app")
// The device-QA driver: typed instrumented tests that drive a device through UiDevice WITHOUT
// suppressing Nudge's accessibility service. A separate, self-instrumenting test APK, so
// `am instrument` never restarts Nudge's process. See qa-driver/build.gradle.kts.
include(":qa-driver")
