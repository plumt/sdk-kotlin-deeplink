# sdk-kotlin-deeplink

딥링크를 **허용 목록으로만** 받아들이는 Kotlin·Android 라이브러리.
URI 를 엄격하게 분해하고, 경로 템플릿과 타입 있는 파라미터로 검증하고, 발신 앱의 신원을 서명까지
확인한 뒤, 인터셉터 사슬을 거쳐 소비자가 고른 라우터(Navigation Compose 든 무엇이든)로 넘긴다.

커스텀 스킴(`myapp://`)은 어떤 앱이든 보낼 수 있고, https App Link 도 아무 앱이나 같은 URI 로 인텐트를
직접 보낼 수 있다. 그래서 기본값은 전부 **거부**다. 등록하지 않은 스킴·호스트·경로·파라미터는
이유([`Rejection`](deeplink-core/src/main/kotlin/net/plumt/deeplink/Rejection.kt))와 함께 거부된다.

| 모듈 | 좌표 | 형태 | 내용 |
|---|---|---|---|
| `deeplink-core` | `net.plumt.deeplink:deeplink-core:0.0.1` | jar (순수 Kotlin) | URI 파서·경로 매칭·파라미터 검증·신뢰 정책·인터셉터·라우터 포트 |
| `deeplink` | `net.plumt.deeplink:deeplink:0.0.1` | aar | 인텐트 해석·발신자 확인(referrer·서명)·extras 정제·자기 전용 인텐트 |

`deeplink` 는 `deeplink-core` 를 `api` 로 노출하므로 Android 앱은 `deeplink` 하나만 넣으면 된다.

## 설치

```kotlin
dependencies {
    implementation("net.plumt.deeplink:deeplink:0.0.1")        // Android
    // implementation("net.plumt.deeplink:deeplink-core:0.0.1") // 서버·데스크톱·멀티 모듈의 도메인 계층
}
```

## 최소 요구사항

- `deeplink-core`: JVM 11 바이트코드. 런타임 의존은 Kotlin 표준 라이브러리뿐이다(코루틴 라이브러리도 쓰지 않는다)
- `deeplink`: minSdk 23, compileSdk 36. 서명 확인은 API 28+ 에서 `SigningInfo`, 그 아래는 `signatures` 로 분기한다.
  Android 14(API 34)+ 에서는 발신 앱이 신원 공유를 켰을 때 OS 가 확인한 발신 패키지를 쓴다
- DI 프레임워크에 의존하지 않는다. 생성은 빌더·팩토리다

## 사용 예

### 경로 선언과 처리기

```kotlin
val itemId = Param.long("id", 1L..Long.MAX_VALUE)
val ref = Param.string("ref", maxLength = 32, pattern = Regex("[a-z0-9_]+"))
val next = Param.httpsUrl("next", allowedHosts = setOf("example.com"))   // 오픈 리다이렉트 방어

val handler = deepLinkHandler {
    route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId); queryParam(ref) })
    route(deepLinkRoute("item-new", "myapp://item/new"))                // item/{id} 보다 우선(더 구체적)
    route(deepLinkRoute("web-item", "https://example.com/item/{id}") { pathParam(itemId) })
    route(deepLinkRoute("login-done", "myapp://login") { queryParam(next, required = true) })
    route(deepLinkRoute("notice", "myapp://notice") {
        requiredTrust(TrustLevel.SELF)                                   // 알림 탭 등 자기 앱만
        unknownQueryParameters(UnknownQueryPolicy.REJECT)
    })
    trustPolicy(AndroidDeepLinks.trustPolicy(context))                  // 자기 패키지를 SELF 로
    interceptor(authInterceptor)
    listener { outcome -> analytics.log(outcome.toString()) }           // toString 에는 값이 없다
    router { link -> navController.navigateTo(link); true }
}
```

결과에서 값을 읽을 때는 선언한 `Param` 을 키로 쓴다. 타입이 보장된다.

```kotlin
is DeepLinkOutcome.Handled -> Unit
// 라우터 안에서
when (link.route.id) {
    "item" -> navController.navigate("item/${link[itemId]}")   // Long
}
```

### Activity 에서

```kotlin
class EntryActivity : ComponentActivity() {
    private val reader by lazy {
        DeepLinkIntentReader.create(
            this,
            trustedCallers = listOf(TrustedCaller("com.example.partner", setOf("AB:CD:…"))),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(reader.read(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatch(reader.read(this, intent))   // 콜백 안에서 곧바로 읽는다
    }

    private fun dispatch(result: IntentReadResult) {
        if (result !is IntentReadResult.Link) return   // NotDeepLink·Unsafe 는 평소 시작 흐름
        lifecycleScope.launch { handler.handle(result.request) }   // 메인 스레드
    }
}
```

### 로그인 후 이어 가기

```kotlin
val authInterceptor = object : DeepLinkInterceptor {
    override val priority = -10
    override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
        if (session.isLoggedIn || chain.link.route.id == "home") chain.proceed()
        else DeepLinkOutcome.Deferred(chain.link, "login")
}

// handle 결과가 Deferred 면 link 를 메모리(ViewModel)에 두고, 로그인 뒤
handler.resume(deferred.link)   // 다시 파싱하지 않고 사슬을 처음부터 돈다
```

### 자기 앱에 보내는 링크(알림)

```kotlin
val pending = AndroidDeepLinks.selfPendingIntent(context, "myapp://notice", requestCode = 1)
// 패키지 고정 + FLAG_IMMUTABLE. FLAG_MUTABLE 은 거부한다
```

## 확장 지점

| 지점 | 인터페이스 | 기본 구현 | 용도 |
|---|---|---|---|
| 경로 매칭 | `RouteMatcher` | `RouteMatcher.template(...)` | 정규식·접두어 등 다른 규칙. `specificity` 로 우선순위 |
| 파라미터 검증 | `ParamValidator<T>` / `Param(name, validator)` | `Param.string`·`int`·`long`·`boolean`·`oneOf`·`uuid`·`httpsUrl` | 자기 형식(날짜·슬러그·서명된 토큰) |
| 신뢰 정책 | `TrustPolicy` | `TrustPolicy.standard(selfPackage)` | 조직 고유 규칙 |
| 흐름 제어 | `DeepLinkInterceptor` | 없음 | 로그인 후 재개·강제 업데이트·계측 |
| 이동 | `DeepLinkRouter` | 없음(필수) | Navigation Compose·Fragment·Activity |
| 관찰 | `DeepLinkListener` | 없음 | 분석·로그 |
| URI 규칙 | `UriRules` | `UriRules.DEFAULT` | 길이 상한·인코딩 정책 |
| 발신자 판정(Android) | `CallerVerifier` | `CallerVerifier.create(context, trustedCallers)` | 다른 신원 근거 |

## 보안

### 막는 공격

| 공격 | 이 라이브러리가 하는 일 |
|---|---|
| **인텐트 리다이렉션** — extras 에 실린 Intent·PendingIntent 를 앱이 그대로 `startActivity` 해 비공개 컴포넌트가 열림 | `DeepLinkIntentReader` 는 data URI 와 발신자만 꺼낸다. extras 의 Intent·Parcelable·Bundle·Uri 를 꺼내는 API 가 아예 없고, `SafeExtras` 는 문자열·정수·불리언만 읽는다. 셀렉터가 붙은 인텐트와 풀 수 없는 extras 는 `Unsafe` 로 거른다. 링크를 다른 링크로 바꿔 넘기는 인터셉터 API 도 없다 |
| **오픈 리다이렉트** — `?next=https://evil.com` 같은 값으로 외부로 보냄 | `Param.httpsUrl` 이 값을 다시 엄격히 파싱해 https·포트 없음·허용 호스트와 **정확히 일치**할 때만 통과시킨다. `user@host`·`//host`·`\`·`example.com.evil.com` 은 모두 거부된다 |
| **발신자 위장** — 외부 앱이 `EXTRA_REFERRER` 에 자기 앱이나 제휴 앱 패키지를 써 넣음 | referrer extra 가 있으면 그 값은 `CLAIMED` 로만 보고 신뢰를 올리지 않는다. 제휴 앱(FIRST_PARTY)은 OS 가 채운 발신 패키지의 **현재 서명 인증서 SHA-256** 이 허용 목록과 모두 맞을 때만 인정한다(교체 이력의 옛 인증서는 보지 않는다). https 라는 사실만으로 신뢰를 올리지 않는다 |
| **파라미터 주입** — 선언하지 않은 쿼리, 중복 이름, 경로 구분자·제어 문자를 끼운 값 | 선언한 이름만 결과에 남고(또는 `REJECT`), 선언한 이름의 중복은 거부한다. `%2F`·`%5C`(새 경로 조각처럼 해석될 값), `.`·`..`, 이중 인코딩(`%252F`), 잘못된 UTF-8, 제어 문자·양방향 서식 문자(`U+202E`)·폭 없는 문자, 사용자 정보, 비 ASCII 호스트(IDN 은 punycode 만)를 거부한다. 모든 길이에 상한이 있다 |
| **검증 우회 객체** | `ResolvedDeepLink` 는 생성자가 공개되지 않고 `copy` 도 없다. 신뢰 수준을 바꿔 낀 링크를 만들 수 없고, `resume` 은 자기가 만든 링크만 받는다 |
| **로그 유출** | 라이브러리는 로그를 쓰지 않는다. 모든 결과·거부 사유·요청의 `toString` 은 URI 원문·파라미터 값을 담지 않는다(`DeepLinkUri.redacted()` 는 경로 조각도 지운다). 검증기 예외도 메시지 대신 타입 이름만 싣는다 |

### 앱이 직접 해야 하는 일

- **App Links**: https 링크를 쓰면 `https://<도메인>/.well-known/assetlinks.json` 을 배포하고 인텐트 필터에
  `android:autoVerify="true"` 를 단다. 그래도 아무 앱이나 같은 URI 로 인텐트를 직접 보낼 수 있으므로,
  이 라이브러리는 https 진입을 기본 `PUBLIC` 으로 본다
- **exported Activity 위생**: 딥링크 진입 Activity 하나만 `exported="true"` 로 두고 나머지는 `false`.
  진입 Activity 는 받은 인텐트를 되넘기지 않는다
- **패키지 가시성**: Android 11+ 에서 `TrustedCaller` 의 서명을 읽으려면 매니페스트에
  `<queries><package android:name="…"/></queries>` 를 적는다. 빠지면 그 앱은 FIRST_PARTY 가 되지 못한다(안전한 쪽으로 실패)
- **민감한 동작**: 결제·계정 변경·삭제는 딥링크 파라미터만으로 실행하지 않는다. 앱 안에서 다시 확인·인증한다
- **사용자 표시**: 거부 사유를 사용자에게 그대로 보이지 않는다("열 수 없는 링크" 정도)
- 매니페스트 인텐트 필터의 스킴·호스트와 등록한 경로를 맞춘다

### 한계

- referrer 는 PendingIntent 의 경우 **만든 앱**을 가리킨다. 제휴 앱이 만든 PendingIntent 를 제3의 앱이 보내면 제휴 앱으로 보인다
- Android 14 미만에서 `EXTRA_REFERRER` 가 실린 인텐트는 OS 가 채운 referrer 를 읽을 방법이 없어 `CLAIMED` 가 된다
- 서명 확인은 PackageManager 를 믿는다. 루팅된 기기는 위협 모델 밖이다

## 스레드

- `DeepLinkHandler.resolve` 는 블로킹 작업이 없다. 어느 스레드에서든 부를 수 있다
- `handle`·`resume` 은 스레드를 바꾸지 않는다. 라우터가 화면을 움직이면 메인 스레드에서 부른다
- `DeepLinkIntentReader.read`·`CallerVerifier.identify` 는 메인 스레드에서 부른다(PackageManager 조회가 짧게 블로킹한다)
- 처리기는 불변이며 동시에 여러 링크를 처리해도 안전하다

## 버전

모듈마다 따로 SemVer 를 쓴다. 태그는 `deeplink-core-v<버전>`, `deeplink-v<버전>`. 변경 내역은 [CHANGELOG.md](CHANGELOG.md),
예외와 실패 신호는 [ERRORS.md](ERRORS.md).

빌드 조건: `./gradlew build` 가 BCV `apiCheck`(공개 API 고정), Robolectric 테스트(API 23·27·28·31·33·34·35),
Kover `koverVerify`(라인 커버리지 90% 이상)를 포함한다.

## 라이선스

Apache License 2.0. [LICENSE](LICENSE) 참고.
