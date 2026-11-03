plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

// Android(D8)·서버 양쪽에서 읽히도록 바이트코드는 Java 11 로 낸다. 빌드 JDK 는 무엇이든 된다
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 런타임 의존은 Kotlin 표준 라이브러리뿐이다. 코루틴은 테스트에서 runBlocking 만 쓴다
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

mavenPublishing {
    coordinates(artifactId = "deeplink-core", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("deeplink-core")
        description.set("딥링크를 허용 목록·경로 템플릿·타입 있는 파라미터로 검증하고 인터셉터 체인을 거쳐 라우팅하는 순수 Kotlin 라이브러리")
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
