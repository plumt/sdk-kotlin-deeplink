pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sdk-kotlin-deeplink"

// 순수 Kotlin 코어(jar)와 Android 어댑터(aar)
include(":deeplink-core")
include(":deeplink")
