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

// 발행 모듈 — 디렉터리 이름이 artifactId 와 같다
include(":deeplink")
// 발행하지 않는다 — SDK 를 의존한 앱의 병합 매니페스트·lint·R8 release 를 빌드마다 확인한다
include(":sample")
