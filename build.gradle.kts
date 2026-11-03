plugins {
    // 루트에도 check 를 두어 합산 koverVerify 가 build 에 걸리게 한다
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}

dependencies {
    // 두 모듈의 커버리지를 루트에서 합쳐 본다
    kover(project(":deeplink-core"))
    kover(project(":deeplink"))
}

kover {
    reports {
        filters {
            excludes {
                // 공개 API 로 나가는 Kotlin 컴파일러 생성 클래스(when 매핑 등)는 테스트 대상이 아니다
                classes("*\$WhenMappings")
            }
        }
        // Android 모듈은 release 변형의 단위 테스트를 함께 집계한다
        total {
            verify {
                rule {
                    // 무관용 요구 — 라인 커버리지 90% 미만이면 check 가 실패한다
                    minBound(90)
                }
            }
        }
    }
}
