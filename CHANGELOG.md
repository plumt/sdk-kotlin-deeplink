# Changelog

0.1.0 부터 아티팩트는 `deeplink` 하나다(0.0.x 의 `deeplink-core` 는 여기에 합쳐졌다). 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.
0.0.x 는 Maven Central 에 게시되지 않았다.

## deeplink

### [Unreleased]

### [0.1.0] - 2026-11-08

#### 깨짐
- 아티팩트를 하나로 합쳤다 — `net.plumt.deeplink:deeplink-core` 는 없어지고 `net.plumt.deeplink:deeplink`(aar) 하나다. 순수 JVM(서버·데스크톱) 사용 경로는 없어졌다. 태그는 `deeplink-v<버전>`
- 패키지를 `net.plumt.deeplink` 하나로 모았다 — `net.plumt.deeplink.android.*` 의 공개 타입(`CallerVerifier`·`TrustedCaller`·`IntentReadResult`·`UnsafeIntentReason`·`ExtraRead`·`ExtraInvalidReason`)은 `net.plumt.deeplink` 로 옮겼다
- 진입점을 `DeepLinks` 하나로 바꿨다(`DeepLinks.create(context, config)`, 구현은 `internal`). 다음 공개 타입·함수가 없어졌다
  - `DeepLinkHandler`·`DeepLinkHandler.Builder`·`deepLinkHandler { }` → `deepLinkConfig { }`(`DeepLinkConfig`) + `DeepLinks`. 라우터는 설정이 아니라 호출마다 넘긴다: `handle(request, router)`·`resume(link, router)`·`consumeWhileResumed(owner, router, onOutcome)`
  - `DeepLinkIntentReader` → `DeepLinks.readLaunch`·`readNewIntent`(허용 액션·신뢰 목록·발신자 판정기는 `DeepLinkConfig`)
  - `DeepLinkQueue` → `DeepLinks.offer`·`offerLaunch`·`offerNewIntent`·`clearPending`·`pendingCount`·`consumeWhileResumed`(상한은 `DeepLinkConfig.queueCapacity`). 처리 람다를 받던 `consumeWhileResumed(owner) { request -> }` 는 라우터·결과 콜백을 받는 형태로 바뀌었다
  - `AndroidDeepLinks.selfIntent`·`selfPendingIntent` → `DeepLinks.selfIntent`·`selfPendingIntent`. `AndroidDeepLinks.trustPolicy(context)` 는 없어졌다 — 정책을 정하지 않으면 `DeepLinks.create` 가 자기 패키지로 `TrustPolicy.standard` 를 쓴다(0.0.x 처리기의 기본은 `standard(null)` 이라 SELF 경로가 열리지 않았다)
  - `SafeExtras` → `DeepLinks.stringExtra`·`intExtra`·`longExtra`·`booleanExtra`
- 공개 `enum` 을 없앴다. 상수 이름은 그대로이고 `values()`·`entries`·`ordinal`·`valueOf` 가 없어졌다. `when` 에는 `else` 를 둔다
  - `TrustLevel`·`UnknownQueryPolicy`·`MalformedReason`·`LimitKind`·`UnsafeIntentReason`·`ExtraInvalidReason` → 상수를 가진 클래스(생성자 비공개, `name` 속성)
  - `CallerVerification` → 공개 생성자가 있는 값 클래스. 직접 구현한 `CallerVerifier`·`TrustPolicy` 가 새 근거를 만든다. 같은 `name` 이면 같은 값
- 결과·상태를 `sealed class` 에서 `sealed interface` 로, 하위 `data class` 를 일반 클래스(+`equals`/`hashCode`/`toString`)로 바꿨다 —
  `DeepLinkResolution`·`DeepLinkOutcome`·`Rejection`·`ParamCheck`·`IntentReadResult`·`ExtraRead`. `copy`·`componentN` 이 없어졌다(구조 분해 선언을 쓰던 곳은 속성을 읽는다).
  `ParamCheck.Valid`·`ExtraRead.Present` 의 `toString` 은 값을 보이지 않는다
- 기본값 인자를 오버로드·빌더로 바꿨다(바이너리 호환을 위해) — `UriRules(...)` 생성자 → `UriRules.builder()…build()`·`toBuilder()`.
  `Param.string`·`int`·`long`·`httpsUrl`·`DeepLinkUri.parse`·`parseOrNull`·`DeepLinkRequest`·`TrustedCaller`·`CallerVerifier.create`·`DeepLinkRoute.Builder.queryParam`·`deepLinkRoute` 은 같은 이름의 오버로드로 남았다.
  이름 붙은 인자로 중간 인자를 건너뛰던 호출(`Param.string("s", pattern = …)`·`TrustedCaller(…, rotationMinSdk = 28)`)은 앞 인자를 함께 적는다
- 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(`-jvm-default=no-compatibility`) — `DeepLinkInterceptor$DefaultImpls`·`RouteMatcher$DefaultImpls` 가 없어졌다(Java 에서 부르던 경우만 해당)
- `resume` 에 다른 인스턴스의 링크를 넘길 때의 메시지가 `이 DeepLinks 가 만든 링크가 아니다` 로 바뀌었다

#### 추가
- `DeepLinks` — 인텐트 읽기·대기열·검증·처리·자기 링크·extras·App Link 상태·외부 링크 열기를 한 진입점에서. 처리는 `suspend`(기준)와 콜백(메인 스레드로 한 번, 취소 핸들 `DeepLinkHandle`) 두 형태
- `offerLaunch`·`offerNewIntent` — 읽고 딥링크면 대기열에 넣는 한 줄
- `DeepLinkConfig`·`deepLinkConfig { }` — 경로·인터셉터·리스너·규칙·신뢰 정책·URI 규칙·신뢰 목록·발신자 판정기·허용 액션·대기열 상한·외부 호스트
- `DeepLinkRule` — 모든 검증을 통과한 링크에 앱이 더하는 동기 규칙. `resolve` 에도 적용되고, 예외는 `Rejection.Custom("rule_exception", <타입>)` 으로 거부
- App Links 검증 상태 `appLinkStatus()`(`AppLinkStatus.Available`·`Unsupported`·`Unavailable`, 호스트별 `AppLinkHostState.VERIFIED`·`SELECTED`·`NONE`, `isVerified`·`opensInApp`) — API 31+ `DomainVerificationManager`. 설정 화면 `appLinkSettingsIntent()`
- 외부 링크 열기 `externalIntent(uri)`·`openExternal(context, uri)`(`ExternalLinkResult`·`ExternalOpenResult`) — https·`externalHosts` 정확히 일치만, `intent:`·`javascript:`·`file:`·`content:` 거부, 컴포넌트·패키지·셀렉터·extras 없는 BROWSABLE 인텐트
- 테스트용 `FakeDeepLinks` — 같은 검증 엔진을 기기 없이. 인텐트 읽기·App Link 상태를 정해 두고, 외부 열기·자기 링크 발급·결과를 기록한다
- `CallerVerification(name)` 공개 생성자, `TrustedCaller` 의 `equals`/`hashCode`
- 확인용 앱 `sample`(발행 안 함) — 병합 매니페스트에 SDK 가 권한·컴포넌트·`<queries>` 를 더하지 않는지, R8 release 가 `consumer-rules.pro` 만으로 되는지 빌드마다 본다
- 일치 테스트 — `ERRORS.md` 가 모든 `Rejection` 코드·`MalformedReason`·`LimitKind` 를, README 가 모든 내장 `Param` 을 적었는지 확인한다

#### 바뀜
- 의존: `kotlinx-coroutines-core` → `kotlinx-coroutines-android`(implementation, 콜백의 메인 디스패처). `androidx.lifecycle:lifecycle-common` 은 그대로 api
- 파일·함수 단위 `@Suppress("NewApi")` 를 없애고 API 분기를 `@RequiresApi` 함수와 실제 기기 API 검사로 격리했다(동작 변화 없음)
- 리소스 접두어 `deeplink_` 를 걸었다(리소스는 아직 없다)
- README 를 `DeepLinks` 하나 기준으로 다시 썼고 지원 범위·API 레벨 표·알려진 한계를 더했다

### [0.0.2] - 2026-11-07

#### 보안 리뷰 반영
- **발신자 판정**: `Activity.getReferrer()` 는 위조할 수 있으므로 어느 경로로 왔든 `CLAIMED` 로만 남긴다.
  OS 보증(`OS_VERIFIED`)은 Android 14+ 의 `launchedFromUid` 와 그 uid 의 패키지(`getPackagesForUid`) 대조로만 준다.
  그래서 Android 14 미만에서는 다른 앱이 FIRST_PARTY 가 되지 않는다
- `DeepLinkIntentReader.read` 를 `readLaunch(activity, savedInstanceState)`·`readNewIntent(activity, intent)` 로 나눴다.
  `onNewIntent` 로 온 링크에 처음 띄운 앱 정보(launchedFrom)를 붙이지 않고, 재생성 때는 링크를 다시 처리하지 않는다.
  `CallerVerifier.identify` 에 `initialLaunch` 인자를 더했다
- `AndroidDeepLinks.selfIntent`·`selfPendingIntent` 가 data URI 에 HMAC 서명 토큰(`#plumt-self=…`)을 붙이고, 리더가
  확인한 뒤 떼어 낸다. 키는 `noBackupFilesDir` 에 있다. 이 토큰만 `SELF_ISSUED`(→ SELF)가 된다. URI 에 `#` 을 쓰면
  거부하고, 키를 준비하지 못하면 `DeepLinkException` 을 던진다
- `trustedCallers` 에 자기 패키지를 넣으면 거부한다
- `UNREADABLE_EXTRAS` 는 extras 구조 손상만 뜻한다고 계약을 고쳤다 — API 33+ 는 알 수 없는 Parcelable 을 감지하지 않는다(역직렬화하지 않으므로 무해). `SafeExtras` 의 역직렬화 대가를 KDoc 에 적었다
- 추가: `TrustedCaller` 의 `previousSha256Digests`·`rotationMinSdk` — 서명 키 교체(v3/v3.1) 시 옛 인증서를 교체 미적용 API 기기에서만 받는다

#### 웜 스타트 소비
- 추가: `DeepLinkQueue` — 프로세스 전역 대기열. `offer`(넘치면 가장 오래된 링크를 버리고 돌려줌, 기본 상한 8)·`clear`·`size`·`capacity`
- 추가: `DeepLinkQueue.consumeWhileResumed(owner, handle)` — RESUMED 인 소비자만 링크를 한 번씩 가져간다. 처리 중 PAUSE 는 보류(복귀하면 이어짐), 처리 중 파괴는 취소하고 맨 앞에 되돌림(끝나는 Activity 면 버림). 돌려준 `Closeable` 로 닫는다
- 의존 추가: `androidx.lifecycle:lifecycle-common`(api, `LifecycleOwner` 가 공개 API 에 나온다), `kotlinx-coroutines-core`(implementation). 내비게이션 라이브러리에는 의존하지 않는다
- 변경: `readLaunch` 가 `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 실행을 `NotDeepLink` 로 본다. 최근 앱 화면에서 다시 열 때 옛 링크가 다시 처리되던 경로를 막았다
- 문서: README 의 `lifecycleScope.launch { handler.handle(...) }` 예시를 대기열로 바꾸고 launchMode·Compose·탭 이동 주의를 더했다. 그 예시는 앱이 떠 있을 때 인스턴스 수만큼 처리되거나, 화면 준비 전에 링크를 잃거나, 뒤에 깔린 화면을 움직일 수 있었다

#### 문서: 하단 탭 이동 규칙
- README 의 탭 규칙을 고쳤다. 코드 변경은 없다(Navigation 쪽 규칙이고 라이브러리 런타임 동작이 아니다). 이전 예제대로 탭 바를 만들면 링크로 연 `item/5` 위에서 첫 탭을 눌러도 `popUpTo(시작) { saveState = true }`·`restoreState = true` 가 그 화면을 곧바로 되살려 탭이 반응하지 않는 것처럼 보였다
- `AppTabs`·`currentTab`·`selectTab`·`navigateToTab` 예제를 새로 썼다. 현재 탭을 다시 누르면 그 탭의 루트로 간다. 링크의 탭 이동은 항상 탭의 루트에 닿는다. 다른 탭을 거쳐 돌아올 때 탭 아닌 화면을 유지할지는 `keepOpenScreens` 로 앱이 고른다. 탭 바 선택 표시도 현재 탭 기준으로 바꿨다(이전 `hierarchy` 판정은 탭 아닌 화면 위에서 아무 탭도 선택하지 않았다)

### [0.0.1]

#### 추가
- `DeepLinkIntentReader` — 액션 허용 목록, data 와 발신자만 꺼냄, 셀렉터·풀 수 없는 extras 는 `Unsafe`
- `CallerVerifier`·`TrustedCaller` — Android 14+ launchedFrom, referrer extra 위장 감지, 현재 서명 인증서 SHA-256 대조(API 28+ `SigningInfo`, 그 아래 `signatures`)
- `SafeExtras` — 원시 타입만 읽는 extras 접근
- `AndroidDeepLinks` — 자기 패키지 신뢰 정책, 패키지 고정 인텐트, `FLAG_IMMUTABLE` PendingIntent

## deeplink-core (0.1.0 에서 deeplink 에 합쳐짐)

### [deeplink-core 0.0.2] - 2026-11-07

#### 보안 리뷰 반영
- **신뢰 모델**: `CallerVerification.SYSTEM_REPORTED` 를 없애고 `SELF_ISSUED` 를 추가했다. `TrustPolicy.standard` 는
  `SELF_ISSUED` 인 자기 패키지만 `SELF`, 자기 패키지가 아닌 `SIGNATURE_VERIFIED` 만 `FIRST_PARTY` 로 본다.
  위조할 수 있는 referrer(`CLAIMED`)와 자기 앱이 중계한 링크(같은 uid 의 `OS_VERIFIED`)는 `PUBLIC` 이다
- 퍼센트 디코딩은 ASCII 16진 숫자만 받는다(아랍·전각 숫자로 이중 인코딩 방어를 우회하던 문제)
- 이중 인코딩 판정: 디코딩한 경로 조각·쿼리 이름, `Param.string` 값에 `%` 가 하나라도 남으면 거부한다
- 스킴·호스트는 소문자화 **전에** ASCII 인지 검사한다(켈빈 기호 `K` 등 유니코드 대소문자 접기 차단)
- 포트 앞자리 0(`:0443`), 정규형이 아닌 IPv4 꼴 호스트(`0x7f.0.0.1`·`127.1`), 짝 없는 서로게이트 원문을 거부한다
- `Param.int`·`long` 이 `-0` 을 거부한다
- 추가: `MalformedReason.NOT_NORMALIZED`·`UriRules.allowUnnormalized` — NFKC 정규형이 아닌 경로 조각·쿼리 이름(전각 `．．`·`／`) 거부
- 추가: `DeepLinkUri.toUriString()` — 검증한 값을 정규형 URL 로 다시 조립(같은 규칙으로 다시 읽으면 같은 값)

#### 실기기 검증 반영
- 수정: Android 에서 `RouteMatcher.template`(곧 모든 `deepLinkRoute`)이 `PatternSyntaxException` 으로 죽던 문제.
  템플릿 자리표시자 정규식의 짝 없는 `}` 를 ICU 정규식이 거부했다(JDK 는 리터럴로 받아 JVM 테스트는 통과).
  중괄호를 모두 이스케이프했고, 레포의 모든 `Regex` 리터럴을 두 엔진 공통 문법으로 검사하는 테스트를 더했다

### [deeplink-core 0.0.1]

#### 추가
- `DeepLinkUri` — 보안상 모호한 형태를 거부하는 자체 URI 파서(사용자 정보·`%2F`·점 조각·이중 인코딩·잘못된 UTF-8·제어·서식 문자·비 ASCII 호스트), 정확히 한 번 디코딩, 값을 지운 `redacted()`
- `UriRules` — URI·경로 조각·쿼리 개수·값 길이 상한과 인코딩 정책
- `RouteMatcher.template` — `scheme://host/literal/{param}` 템플릿, 구체성 우선 매칭
- `Param` 과 검증기 `string`·`int`·`long`·`boolean`·`oneOf`·`uuid`·`httpsUrl`(오픈 리다이렉트 방어)
- `DeepLinkRoute` 빌더·DSL — 필수·선택 쿼리, 선언 외 쿼리 정책(`DROP`·`REJECT`), 요구 신뢰 수준
- `TrustLevel`·`CallerIdentity`·`CallerVerification`·`TrustPolicy.standard`
- `DeepLinkHandler` — `resolve`·`handle`·`resume`, 인터셉터 사슬(`priority`), 라우터 포트, 결과 리스너
- `Rejection` 거부 사유(값을 담지 않음)와 `DeepLinkOutcome`(`Handled`·`Unhandled`·`Rejected`·`Deferred`·`Failed`)
