import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    // BCV 가 .api 를 만들 수 있도록 내장 Kotlin 대신 kotlin-android 를 건다(gradle.properties 참고)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "net.plumt.deeplink"
    compileSdk = 36
    // 리소스를 더하면 이 접두어를 붙인다 — 앱 리소스와 이름이 겹치지 않게
    resourcePrefix = "deeplink_"

    defaultConfig {
        // 서명 확인은 28+, 발신자 OS 보증은 34+, App Links 검증 상태는 31+ 를 런타임 분기한다
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 은 API 레벨마다 android-all 을 올린다 — 여러 API 를 도는 테스트가 기본 힙(512m)을 넘는다
        unitTests.all { it.maxHeapSize = "1536m" }
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(DefaultImpls 없음) — 인터페이스에 함수를 더해도 구현체가 깨지지 않는다
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // consumeWhileResumed 가 LifecycleOwner 를 받는다. 내비게이션·Activity 라이브러리에는 의존하지 않는다
    api(libs.androidx.lifecycle.common)
    // 소비 코루틴과 콜백 호출의 메인 디스패처(공개 API 에 코루틴 타입은 나오지 않는다)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.lifecycle.runtime)
    testImplementation(libs.androidx.activity)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "deeplink", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("deeplink")
        description.set("딥링크를 허용 목록·경로 템플릿·타입 있는 파라미터·발신자 판정으로 검증하고 앞 화면에서 한 번만 처리하는 Android 라이브러리")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
                // Kotlin 컴파일러 생성 클래스(when 매핑 등)는 테스트 대상이 아니다
                classes("*\$WhenMappings")
            }
        }
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify(=check) 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") { dependsOn("koverVerify") }
