import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    // BCV 가 .api 를 만들 수 있도록 내장 Kotlin 대신 kotlin-android 를 건다(gradle.properties 참고)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "net.plumt.deeplink.android"
    compileSdk = 36

    defaultConfig {
        // ActivityOptions·Activity.referrer 는 22+, 서명 확인 API 는 28+ 를 런타임 분기한다
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 공개 API 가 코어 타입(DeepLinkRequest·CallerIdentity)을 드러내므로 api 로 노출한다
    api(project(":deeplink-core"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.core)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "deeplink", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("deeplink")
        description.set("Android 딥링크 진입점의 인텐트 해석·발신자 서명 확인·extras 정제를 맡는 라이브러리")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        verify {
            rule {
                // 무관용 요구 — 이 모듈 라인 커버리지가 90% 미만이면 check(=build) 가 실패한다
                minBound(90)
            }
        }
    }
}
