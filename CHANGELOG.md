# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## deeplink-core

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `DeepLinkUri` — 보안상 모호한 형태를 거부하는 자체 URI 파서(사용자 정보·`%2F`·점 조각·이중 인코딩·잘못된 UTF-8·제어·서식 문자·비 ASCII 호스트), 정확히 한 번 디코딩, 값을 지운 `redacted()`
- `UriRules` — URI·경로 조각·쿼리 개수·값 길이 상한과 인코딩 정책
- `RouteMatcher.template` — `scheme://host/literal/{param}` 템플릿, 구체성 우선 매칭
- `Param` 과 검증기 `string`·`int`·`long`·`boolean`·`oneOf`·`uuid`·`httpsUrl`(오픈 리다이렉트 방어)
- `DeepLinkRoute` 빌더·DSL — 필수·선택 쿼리, 선언 외 쿼리 정책(`DROP`·`REJECT`), 요구 신뢰 수준
- `TrustLevel`·`CallerIdentity`·`CallerVerification`·`TrustPolicy.standard`
- `DeepLinkHandler` — `resolve`·`handle`·`resume`, 인터셉터 사슬(`priority`), 라우터 포트, 결과 리스너
- `Rejection` 거부 사유(값을 담지 않음)와 `DeepLinkOutcome`(`Handled`·`Unhandled`·`Rejected`·`Deferred`·`Failed`)

## deeplink

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `DeepLinkIntentReader` — 액션 허용 목록, data 와 발신자만 꺼냄, 셀렉터·풀 수 없는 extras 는 `Unsafe`
- `CallerVerifier`·`TrustedCaller` — Android 14+ launchedFrom, referrer extra 위장 감지, 현재 서명 인증서 SHA-256 대조(API 28+ `SigningInfo`, 그 아래 `signatures`)
- `SafeExtras` — 원시 타입만 읽는 extras 접근
- `AndroidDeepLinks` — 자기 패키지 신뢰 정책, 패키지 고정 인텐트, `FLAG_IMMUTABLE` PendingIntent
