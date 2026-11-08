# sdk-kotlin-deeplink

딥링크를 **허용 목록으로만** 받아들이는 Android 라이브러리.
URI 를 엄격하게 분해하고, 경로 템플릿과 타입 있는 파라미터로 검증하고, 발신자를 플랫폼이 보증한 값으로만
판정한 뒤, 인터셉터 사슬을 거쳐 화면이 준 라우터(Navigation Compose 든 무엇이든)로 넘긴다.
링크는 앞에 선 화면이 한 번만 처리한다.

커스텀 스킴(`myapp://`)은 어떤 앱이든 보낼 수 있고, https App Link 도 아무 앱이나 같은 URI 로 인텐트를
직접 보낼 수 있다. 그래서 기본값은 전부 **거부**다. 등록하지 않은 스킴·호스트·경로·파라미터는
이유([`Rejection`](deeplink/src/main/kotlin/net/plumt/deeplink/Rejection.kt))와 함께 거부된다.

```kotlin
dependencies {
    implementation("net.plumt.deeplink:deeplink:0.1.0")
}
```

진입점은 `DeepLinks` 하나다. 아티팩트도 하나이고(aar), 0.0.x 의 `deeplink-core` 는 여기에 합쳐졌다.

## 빠른 시작

`DeepLinks` 를 프로세스에 하나 만들어(`Application`·DI 싱글턴) 넘긴다. 대기열과 재개(`resume`)가 그 인스턴스에 묶인다.

```kotlin
val itemId = Param.long("id", 1L..Long.MAX_VALUE)

class App : Application() {
    val deepLinks by lazy {
        DeepLinks.create(this, deepLinkConfig {
            route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
            route(deepLinkRoute("web-item", "https://example.com/item/{id}") { pathParam(itemId) })
            route(deepLinkRoute("notice", "myapp://notice") { requiredTrust(TrustLevel.SELF) })   // 알림 탭 등 자기 앱만
        })
    }
}

class MainActivity : ComponentActivity() {
    private val deepLinks get() = (application as App).deepLinks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinks.offerLaunch(this, savedInstanceState)                       // 재생성(회전·복원)이면 넣지 않는다
        deepLinks.consumeWhileResumed(this, { link -> showItem(link[itemId]); true }) { outcome ->
            if (outcome !is DeepLinkOutcome.Handled && outcome !is DeepLinkOutcome.Deferred) showHome()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.offerNewIntent(this, intent)                                // 처음 띄운 앱 정보를 이 링크에 붙이지 않는다
    }
}
```

- 처리는 같은 동작을 두 형태로 준다: `suspend` `handle(request, router)`(기준)와 콜백 `handle(request, router) { outcome -> }`(메인 스레드로 한 번, 취소 핸들 반환). `resume` 도 같다
- 결과(`DeepLinkOutcome`·`DeepLinkResolution`·`IntentReadResult`·`ExtraRead`·`AppLinkStatus`·`ExternalLinkResult`·`ExternalOpenResult`·`Rejection`·`ParamCheck`)는 닫힌 `sealed interface` 라 `when` 에 `else` 가 필요 없다.
  값이 늘 수 있는 상수 집합(`TrustLevel`·`CallerVerification`·`UnknownQueryPolicy`·`MalformedReason`·`LimitKind`·`UnsafeIntentReason`·`ExtraInvalidReason`·`AppLinkHostState`)은 열거형이 아닌 클래스이고, `when` 으로 가를 때는 `else` 를 둔다
- 앱 단위 테스트에서는 [`FakeDeepLinks`](#테스트) 로 바꾼다

매니페스트에는 `singleTop` 을 준다([launchMode](#launchmode)).

```xml
<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="myapp" />
    </intent-filter>
</activity>
```

## 지원 범위

| 기능 | API |
|---|---|
| 허용 목록 검증 — 스킴·호스트·경로 템플릿(`{name}`)·구체성 우선 매칭, 선언 외 쿼리 정책 | `deepLinkRoute`·`DeepLinkRoute.builder`·`RouteMatcher` |
| 타입 있는 파라미터 — 내장 검증기 [아래 목록](#내장-파라미터) | `Param`·`ParamValidator` |
| 보안 URI 파서 — 사용자 정보·`%2F`·점 조각·이중 인코딩·NFKC 비정규형·제어·서식 문자·비 ASCII 호스트 거부, 정확히 한 번 디코딩 | `DeepLinkUri`·`UriRules` |
| 발신자 판정 — 자기 링크 HMAC 토큰(SELF), Android 14+ OS 보증 uid + 서명 대조(FIRST_PARTY), 위조 가능한 referrer 는 참고로만 | `readLaunch`·`readNewIntent`·`CallerVerifier`·`TrustedCaller`·`TrustPolicy` |
| 인텐트 위생 — data·발신자만 꺼냄, 셀렉터·손상된 extras 는 `Unsafe`, extras 는 원시 타입만 | `IntentReadResult`·`stringExtra`·`intExtra`·`longExtra`·`booleanExtra` |
| 앞 화면 한 번 처리 — 프로세스 전역 대기열, RESUMED 소비자만, PAUSE 보류·파괴 시 되돌림, 상한 | `offer`·`offerLaunch`·`offerNewIntent`·`consumeWhileResumed`·`clearPending` |
| 흐름 제어 — 인터셉터 사슬, 미루고 재개, 앱 규칙 | `DeepLinkInterceptor`·`resume`·`DeepLinkRule` |
| 자기 앱 링크 — 패키지 고정, 서명 토큰, `FLAG_IMMUTABLE` | `selfIntent`·`selfPendingIntent` |
| App Links 검증 상태(API 31+)와 설정 화면 | `appLinkStatus`·`appLinkSettingsIntent` |
| 외부 링크 열기 — https·허용 호스트만, `intent:`·`javascript:`·`file:`·`content:` 거부, BROWSABLE·컴포넌트 없음 | `externalIntent`·`openExternal` |
| 테스트용 가짜 — 같은 검증 엔진을 JVM 에서 | `FakeDeepLinks` |

### 내장 파라미터

| 검증기 | 받는 값 |
|---|---|
| `Param.string(name[, maxLength[, pattern[, allowPercentEscapes]]])` | 비어 있지 않은 문자열. 기본 상한 256자, `%` 가 남은 값(이중 인코딩) 거부 |
| `Param.int(name[, range])` | 10진 정수. 앞자리 0·`+`·`-0` 거부 |
| `Param.long(name[, range])` | 10진 Long. 표기 규칙은 `int` 와 같다 |
| `Param.boolean(name)` | `true`·`false`(소문자)만 |
| `Param.oneOf(name, allowed)` | 정해진 값 중 하나(대소문자 구분) |
| `Param.uuid(name)` | 하이픈 있는 표준 UUID |
| `Param.httpsUrl(name, allowedHosts[, rules])` | 오픈 리다이렉트 방어 — https·포트 없음·허용 호스트와 정확히 일치 |

자기 형식(날짜·슬러그·서명된 토큰)은 `Param(name, ParamValidator { raw -> ... })` 로 더한다.

## 사용 예

### 경로와 검증

```kotlin
val ref = Param.string("ref", 32, Regex("[a-z0-9_]+"))
val next = Param.httpsUrl("next", setOf("example.com"))     // 오픈 리다이렉트 방어

val config = deepLinkConfig {
    route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId); queryParam(ref) })
    route(deepLinkRoute("item-new", "myapp://item/new"))                // item/{id} 보다 우선(더 구체적)
    route(deepLinkRoute("login-done", "myapp://login") { queryParam(next, true) })
    route(deepLinkRoute("strict", "myapp://strict") { unknownQueryParameters(UnknownQueryPolicy.REJECT) })
    interceptor(authInterceptor)
    rule { link -> if (link.route.id == "item-new" && !remoteConfig.newItemOpen) Rejection.Custom("closed", "원격 설정") else null }
    listener { outcome -> analytics.log(outcome.toString()) }           // toString 에는 값이 없다
}
```

결과에서 값을 읽을 때는 선언한 `Param` 을 키로 쓴다. 타입이 보장된다.

```kotlin
val router = DeepLinkRouter { link ->
    when (link.route.id) {
        "item" -> navController.navigate("item/${link[itemId]}")        // Long
        "login-done" -> openInBrowser(link[next].toUriString())         // 검증한 정규형. 원래 문자열을 다른 파서로 다시 읽지 않는다
    }
    true
}
```

`DeepLinkRule` 은 경로·신뢰·파라미터 검증을 모두 통과한 링크에 적용되고 `resolve` 에도 걸린다(인터셉터는 `handle` 에서만 돈다).
예외를 던지면 `Rejection.Custom("rule_exception", <예외 타입>)` 으로 거부된다.

### Compose·Navigation 에서

라이브러리는 `NavController` 를 모른다. 라우터가 그 인스턴스의 컨트롤러를 쓰면 된다. 소비자는 효과로 붙인다 —
`NavHost` 가 첫 구성에서 그래프를 정한 **뒤에** 붙으므로 컨트롤러가 준비되지 않은 채 링크를 가져가지 않는다. 그 전까지 링크는 큐에 남는다.
아래 효과는 라이브러리에 들어 있지 않다(Compose 의존을 기본 아티팩트에 넣지 않는다). 앱에 복사해서 쓴다.

```kotlin
@Composable
fun DeepLinkEffect(deepLinks: DeepLinks, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val latestRouter by rememberUpdatedState(router)
    val latestOutcome by rememberUpdatedState(onOutcome)
    DisposableEffect(deepLinks, owner) {
        val consumer = deepLinks.consumeWhileResumed(owner, { latestRouter.route(it) }) { latestOutcome(it) }
        onDispose { consumer.close() }
    }
}

setContent {
    val nav = rememberNavController()
    AppScaffold(nav)                                                   // NavHost·탭 바
    DeepLinkEffect(deepLinks, { link ->                                // NavHost 바깥에서
        when (link.route.id) {
            "item" -> nav.navigate("item/${link[itemId]}")
            "search" -> nav.navigateToTab("search", tabs)
        }
        true
    })
}
```

- `DeepLinkEffect` 는 **`NavHost` 목적지 안에 두지 않는다.** 거기서 `LocalLifecycleOwner` 는 그 목적지이므로 다른 화면으로 가면 소비가 멈춘다
- **`composable(deepLinks = …)`·`navDeepLink` 를 쓰지 않는다.** `NavController` 가 그래프를 정할 때 `activity.intent` 를 직접 처리해 이 라이브러리의 검증을 건너뛰고, 같은 링크가 두 번 열린다
- 화면이 링크를 받을 준비가 안 됐으면(로그인 화면 등) 준비될 때까지 `DeepLinkEffect` 를 그리지 않는다. 링크는 큐에 남는다. 버려야 하면 `deepLinks.clearPending()`
- 하단 탭이 있으면 탭으로 가는 링크는 그냥 `navigate` 하지 않는다([하단 탭과 함께 쓸 때](#하단-탭과-함께-쓸-때))

### 하단 탭과 함께 쓸 때

**이 절은 Navigation 쪽 규칙이고 이 라이브러리의 런타임 동작이 아니다.** 라이브러리는 `NavController` 를 모르고 아래 코드를 포함하지 않는다. 앱에 복사해서 쓴다(navigation-compose 2.8.5 로 확인했다).

[공식 문서](https://developer.android.com/guide/navigation/backstack/multi-back-stacks)의 탭 이동 옵션 `popUpTo(시작) { saveState = true }`·`launchSingleTop`·`restoreState = true` 는 탭 **사이** 이동용이고, 현재 탭을 다시 눌렀을 때의 동작은 공식 문서가 정하지 않는다. 이 옵션을 재선택에도 그대로 쓰면 스택 `[home, item/5]` 에서 home 을 눌렀을 때 item/5 를 저장했다가 곧바로 되살려, 탭이 반응하지 않는 것처럼 보인다. `saveState`·`restoreState` 중 하나만 빼도 증상은 사라지지만 그러면 탭 상태를 잃으므로, 재선택을 따로 처리한다. 재선택을 탭 루트로 보내는 것은 이 예제의 선택이다. 앱이 다르게 정해도 된다.

| 상황 | 이동 | 결과 |
|---|---|---|
| 지금 탭을 다시 누름 | `selectTab` | 그 탭의 루트. 위에 쌓인 화면은 저장하지 않고 버린다. 이미 루트면 이동 없음 |
| 다른 탭을 눌렀다 돌아옴 | `selectTab` | 앱이 고른다. `keepOpenScreens = true` 면 열어 둔 화면으로 돌아오고 한 번 더 누르면 루트, `false` 면 탭을 떠날 때 그 화면을 버려 항상 루트. 탭 루트 자체의 상태(`rememberSaveable`)는 어느 쪽이든 유지된다 |
| 링크로 탭 | `navigateToTab` | 그 탭의 루트. 이미 루트면 이동 없음 |
| 링크로 탭 아닌 화면 | `navigate("item/5")` | 지금 탭 위에 쌓인다. 탭 바에서는 그 탭이 계속 선택된 상태이고, 뒤로 가기는 그 탭의 루트로 간다 |

```kotlin
// routes 의 첫 항목이 그래프의 시작 목적지다
class AppTabs(val routes: List<String>, val keepOpenScreens: Boolean = true)

// 스택은 [첫 탭, (다른 탭), 탭 아닌 화면…] 꼴이므로 스택에 있는 마지막 탭이 지금 탭이다
fun NavController.currentTab(tabs: AppTabs): String? =
    tabs.routes.lastOrNull { route ->
        try {
            getBackStackEntry(route)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

// 탭 바가 부른다
fun NavController.selectTab(route: String, tabs: AppTabs) {
    val current = currentTab(tabs)
    if (route == current) popBackStack(route, inclusive = false) else switchTab(route, current, tabs)
}

// 링크가 부른다. 저장해 둔 화면이 있어도 탭의 루트에 닿는다
fun NavController.navigateToTab(route: String, tabs: AppTabs) {
    if (currentDestination?.route == route) return
    val current = currentTab(tabs)
    if (route != current) switchTab(route, current, tabs)
    popBackStack(route, inclusive = false)
}

private fun NavController.switchTab(route: String, current: String?, tabs: AppTabs) {
    if (!tabs.keepOpenScreens && current != null) popBackStack(current, inclusive = false)
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

```kotlin
val tabs = AppTabs(listOf("home", "search", "profile"))

@Composable
fun AppBottomBar(nav: NavController) {
    val entry by nav.currentBackStackEntryAsState()               // 스택이 바뀌면 다시 그린다
    val current = entry?.let { nav.currentTab(tabs) }
    NavigationBar {
        tabs.routes.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,                        // hierarchy 로 고르면 item/5 위에서 아무 탭도 선택되지 않는다
                onClick = { nav.selectTab(tab, tabs) },
                icon = { Text("-") },
                label = { Text(tab) },
            )
        }
    }
}
```

- **탭 route 를 그냥 `navigate` 하지 않는다.** 그 탭이 첫 탭 위에 쌓여 스택 꼴이 깨지고, 첫 탭을 눌러도 링크로 연 화면에 머문다
- 시작 목적지가 첫 탭이 아니면(스플래시를 `inclusive` 로 걷어 낸 앱 등) `popUpTo` 가 무시되어 이 코드의 전제가 깨진다. 그때는 기준을 첫 탭 route 로 바꾼다
- `keepOpenScreens = true` 의 복원은 Navigation 의 저장 상태를 따르므로 **뒤로 가기가 섞이면 결과가 달라진다.** 다른 탭에서 뒤로 가기로 돌아온 뒤 연 화면은 복원되지 않을 수 있고, 저장해 둔 화면은 뒤로 가기로 돌아온 뒤에도 남아 있다가 탭을 오갈 때 되살아난다. 항상 같은 결과가 필요하면 `false` 로 둔다

### launchMode

| launchMode | 앱이 떠 있을 때 링크가 오면 | 결과 |
|---|---|---|
| `singleTop` (**이것을 쓴다**) | 같은 태스크 맨 위가 이 Activity 면 그 인스턴스의 `onNewIntent`. 맨 위가 다른 Activity 면 새 인스턴스 | 앱을 보고 있을 때 링크가 와도 인스턴스가 늘지 않고 지금 화면에서 이동한다 |
| `standard` (기본값) | 항상 새 인스턴스의 `onCreate`. `onNewIntent` 는 오지 않는다 | 링크마다 인스턴스·`NavController`·백스택이 새로 생겨 뒤로 가기를 링크 수만큼 눌러야 하고, 이전 화면은 뒤에 숨는다. 대기열은 중복 처리를 막을 뿐 인스턴스가 느는 것은 막지 못한다 |
| `singleTask` | 이미 있으면 그 인스턴스의 `onNewIntent` 로 오고 **그 위의 Activity 를 모두 끝낸다** | 링크 하나에 사용자가 보던 위쪽 화면이 사라진다. 앱 구조가 그것을 원할 때만 쓴다 |

- **`singleTop` 이어도 인스턴스가 하나라고 보장되지는 않는다.** 맨 위가 다른 Activity 이거나, 발신 앱이 `FLAG_ACTIVITY_NEW_TASK` 없이 보내 발신 앱 태스크에 들어오면 새 인스턴스가 생긴다. 그래서 큐를 프로세스 전역(`DeepLinks` 인스턴스)에 두고 RESUMED 인 쪽만 소비한다
- `singleTop`·`singleTask` 에서는 **`onNewIntent` 를 재정의하지 않으면 링크가 그대로 사라진다.** 플랫폼은 새 인텐트를 `getIntent()` 에 넣어 주지 않으므로 필요하면 `setIntent(intent)` 를 부르되, 처리 대상은 매개변수 `intent` 다
- `onNewIntent` 로 온 링크는 `readNewIntent` 라서 `OS_VERIFIED`·`FIRST_PARTY` 가 나오지 않는다([신뢰 모델](#신뢰-모델)). 제휴 앱 링크가 앱이 떠 있을 때도 FIRST_PARTY 여야 하면 별도의 `standard` 진입 Activity 가 필요하다

### 대기열의 동작

| 상황 | 동작 |
|---|---|
| 소비자가 없거나 RESUMED 가 아님 | 큐에 남는다. 처음 RESUMED 인 소비자가 가져간다 |
| 인스턴스가 여럿, 또는 분할 화면으로 둘 다 RESUMED | 링크마다 먼저 가져간 소비자 하나만 처리한다 |
| 회전·재생성 | 처리 끝난 링크는 큐에 없고 `readLaunch` 는 `NotDeepLink` 라 다시 처리되지 않는다. 최근 앱 화면에서 다시 연 실행도 `NotDeepLink` 다 |
| 처리 중 PAUSE | **취소하지 않고 보류한다.** 메인 스레드 코드는 RESUMED 일 때만 이어지므로 복귀하면 이어서 끝나고, 멈춘 화면이 라우터를 부르지 않는다 |
| 처리 중 파괴·`close()` | 처리를 취소하고 같은 요청을 맨 앞에 되돌려 다음 소비자가 처음부터 처리한다. `isFinishing` 인 Activity 면 버린다. 다시 처리되므로 라우터를 부른 뒤에는 suspend 하지 않는다 |
| 큐가 가득 참(기본 8개, `queueCapacity`) | 가장 오래된 링크를 버리고 `offer` 가 그 링크를 돌려준다. 쌓인 링크는 복귀할 때 차례로 처리되므로 값을 크게 잡지 않는다 |
| 프로세스 종료 | 함께 사라진다. 디스크에 쓰지 않는다. 처음 띄운 인텐트는 플랫폼이 다시 주지만 저장된 상태가 있으면 처리한 것으로 본다 |

### 로그인 후 이어 가기

```kotlin
val authInterceptor = object : DeepLinkInterceptor {
    override val priority = -10
    override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
        if (session.isLoggedIn || chain.link.route.id == "home") chain.proceed()
        else DeepLinkOutcome.Deferred(chain.link, "login")
}

// 결과가 Deferred 면 link 를 메모리(ViewModel)에 두고, 로그인 뒤
deepLinks.resume(deferred.link, router)   // 다시 파싱하지 않고 사슬을 처음부터 돈다
```

`resume` 은 링크를 만든 `DeepLinks` 인스턴스만 받는다. 인스턴스를 프로세스에 하나 두면 회전을 건너가도 된다.
인터셉터가 끝나지 않으면 그 소비자의 다음 링크가 기다리므로, 오래 걸리는 조건은 `Deferred` 로 돌려준다.

### 자기 앱에 보내는 링크(알림)

```kotlin
val pending = deepLinks.selfPendingIntent("myapp://notice", requestCode = 1)
// 패키지 고정 + FLAG_IMMUTABLE + 이 앱만 만들 수 있는 서명 토큰. FLAG_MUTABLE 은 거부한다
```

`selfIntent`·`selfPendingIntent` 로 만든 링크만 `SELF` 가 된다. 이 함수에 **외부에서 온 문자열**(채팅 메시지·
웹 페이지·다른 사용자가 고른 링크)을 넣지 않는다 — 넣으면 그 링크가 SELF 경로를 연다. 그런 링크는
[`openExternal`](#외부-링크-열기) 로 열거나 `startActivity(Intent(ACTION_VIEW, uri))` 로 그대로 연다. 같은 앱이 띄웠더라도 토큰이 없으면 `PUBLIC` 이다.

### App Links 검증 상태

```kotlin
when (val status = deepLinks.appLinkStatus()) {
    is AppLinkStatus.Available ->
        if (!status.opensInApp("example.com")) showOpenByDefaultHint { startActivity(deepLinks.appLinkSettingsIntent()) }
    AppLinkStatus.Unsupported, AppLinkStatus.Unavailable -> Unit   // API 31 미만이거나 플랫폼이 상태를 주지 않음
}
```

- `Available.hosts` 는 매니페스트의 `autoVerify` 인텐트 필터 호스트별 상태(`VERIFIED`·`SELECTED`·`NONE`), `linkHandlingAllowed` 는 사용자의 "지원되는 링크 열기" 설정이다
- `isVerified(host)` 는 `assetlinks.json` 검증 여부, `opensInApp(host)` 는 지금 그 링크가 앱으로 바로 오는지(허용 켜짐 + 검증 또는 사용자 선택)다
- **검증 상태는 신뢰 근거가 아니다.** 검증된 호스트여도 아무 앱이나 같은 URI 로 인텐트를 직접 보낼 수 있어 https 진입은 기본 `PUBLIC` 이다

### 외부 링크 열기

채팅 메시지·웹 페이지·서버 응답의 URL 을 앱 밖으로 열 때 쓴다. WebView 의 `shouldOverrideUrlLoading` 에서 `Intent.parseUri` 로 `intent://` 를 풀어
그대로 `startActivity` 하면 페이지가 고른 컴포넌트·패키지·extras 가 열린다(인텐트 리다이렉션). 이 함수는 그 재료를 아예 받지 않는다.

```kotlin
val config = deepLinkConfig {
    // …
    externalHost("example.com")
    externalHost("help.example.com")      // 하위 도메인은 따로 적는다
}

when (deepLinks.openExternal(activity, url)) {
    ExternalOpenResult.Opened -> Unit
    is ExternalOpenResult.Rejected -> showToast("열 수 없는 링크")
    ExternalOpenResult.NoActivity -> showToast("열 앱이 없다")
}
```

- 스킴은 `https` 만. `intent:`·`javascript:`·`file:`·`content:`·`android-app:`·커스텀 스킴은 `Rejection.UnknownScheme`(또는 형식 오류)
- 호스트는 `externalHosts` 와 정확히 일치하고 포트가 없어야 한다. 아니면 `Rejection.UnknownHost`. 기본은 빈 목록 — 모두 거부
- 인텐트는 `ACTION_VIEW` + `CATEGORY_BROWSABLE`, 컴포넌트·패키지·셀렉터·extras 없음. data 는 검증한 정규형(`toUriString`)이고 프래그먼트는 버린다
- 열지 않고 인텐트만 받으려면 `externalIntent(url)`

## 확장 지점

| 지점 | 인터페이스 | 기본 구현 | 용도 |
|---|---|---|---|
| 경로 매칭 | `RouteMatcher` | `RouteMatcher.template(...)` | 정규식·접두어 등 다른 규칙. `specificity` 로 우선순위 |
| 파라미터 검증 | `ParamValidator<T>` / `Param(name, validator)` | [내장 파라미터](#내장-파라미터) | 자기 형식(날짜·슬러그·서명된 토큰) |
| 검증 규칙 | `DeepLinkRule` | 없음 | 파라미터 조합·원격 설정으로 경로 끄기 |
| 신뢰 정책 | `TrustPolicy` | `TrustPolicy.standard(selfPackage)` | 조직 고유 규칙 |
| 발신자 판정 | `CallerVerifier` | `CallerVerifier.create(context, trustedCallers)` | 다른 신원 근거. 새 근거는 `CallerVerification("SERVER_SIGNED")` 처럼 만든다 |
| 흐름 제어 | `DeepLinkInterceptor` | 없음 | 로그인 후 재개·강제 업데이트·계측 |
| 이동 | `DeepLinkRouter` | 없음(호출마다) | Navigation Compose·Fragment·Activity |
| 관찰 | `DeepLinkListener` | 없음 | 분석·로그 |
| URI 규칙 | `UriRules.builder()` | `UriRules.DEFAULT` | 길이 상한·인코딩 정책 |

공개 인터페이스에 메서드가 늘면 기본 구현을 둔다(Java 기본 메서드) — 직접 구현한 코드가 깨지지 않는다.

## 테스트

`FakeDeepLinks` 는 같은 아티팩트에 있고 **기본 구현과 같은 검증 엔진**을 쓴다. 앱의 경로 설정을 기기·Robolectric 없이 JVM 단위 테스트로 시험한다.

```kotlin
val fake = FakeDeepLinks(appDeepLinkConfig, selfPackage = "com.example.app")
val outcome = fake.handle(DeepLinkRequest("myapp://item/7"), router)                     // 실제 검증
val notice = DeepLinkRequest("myapp://notice", CallerIdentity("com.example.app", CallerVerification.SELF_ISSUED))
fake.launchResult = IntentReadResult.Link(notice)                                       // readLaunch 가 돌려줄 값
fake.appLinkStatusResult = AppLinkStatus.Available(true, mapOf("example.com" to AppLinkHostState.VERIFIED))
fake.openExternal(context, "https://example.com/a")                                     // 열지 않고 openedExternal 에 남긴다
```

`selfIntent`·`selfPendingIntent`(`pendingIntentContext` 필요)·`appLinkSettingsIntent`·`externalIntent` 의 `Ready`·`consumeWhileResumed`·콜백 `handle` 은 Android 클래스를 쓰므로 Robolectric 에서 부른다.

## 보안

### 신뢰 모델

신뢰 수준(`TrustLevel`)은 "발신 앱이 무엇이라고 주장하는가" 가 아니라 **플랫폼이나 앱 자신의 비밀이 무엇을
보증하는가** 로만 정한다. 기본 정책 `TrustPolicy.standard(selfPackage)`(정책을 정하지 않으면 자기 패키지로 쓴다):

| 근거 (`CallerVerification`) | 무엇 | 기본 신뢰 |
|---|---|---|
| `SELF_ISSUED` | data URI 에 `selfIntent` 가 붙인 HMAC 토큰이 맞음. 키는 앱 전용 저장소(`noBackupFilesDir`)에 있어 다른 앱이 만들 수 없다. 토큰은 URI 문자열 전체에 묶인다 | `SELF` (자기 패키지일 때) |
| `SIGNATURE_VERIFIED` | `OS_VERIFIED` 인 다른 패키지이고 서명 인증서가 `TrustedCaller` 와 맞음 | `FIRST_PARTY` |
| `OS_VERIFIED` | Android 14+ 에서 Activity 를 **처음** 띄운 인텐트의 `launchedFromUid` 가 그 `launchedFromPackage` 를 실제로 가짐(PackageManager 대조). 발신 앱이 신원 공유를 켰거나 같은 uid 일 때만 OS 가 채운다 | `PUBLIC` |
| `CLAIMED` | `Activity.getReferrer()`·`EXTRA_REFERRER`. 플랫폼 문서가 "위조할 수 있다" 고 적은 값이라 참고로만 남긴다 | `PUBLIC` |
| `NONE` | 정보 없음(브라우저·알림 그늘 등) | `PUBLIC` |

- **자기 앱이 띄웠다는 사실만으로는 SELF 가 아니다.** 앱이 채팅 링크·WebView 의 `intent://`·푸시 페이로드를
  그대로 열면 같은 uid 가 띄운 인텐트가 되지만, 그 URI 는 외부가 골랐다(혼동된 대리인). 그래서 같은 uid 의
  `OS_VERIFIED` 도 `PUBLIC` 이고, 앱이 의도해 발급한 `selfIntent` 만 `SELF` 다
- **Android 14 미만에서는 다른 앱이 FIRST_PARTY 가 될 수 없다.** 그 기기들에는 발신자를 보증하는 플랫폼 값이
  없다(referrer 는 위조된다). 제휴 앱은 Android 14+ 에서 `ActivityOptions.makeBasic().setShareIdentityEnabled(true)`
  로 보내야 한다. 다른 근거(서버가 서명한 토큰 등)가 있으면 `CallerVerifier` 를 직접 구현한다
- `onNewIntent` 로 온 링크(`readNewIntent`)는 처음 띄운 앱 정보를 쓰지 않으므로 `OS_VERIFIED`·`FIRST_PARTY` 가
  나오지 않는다. 자기 링크 토큰은 그대로 확인된다
- 앱 안 이동(SELF)은 가능하면 딥링크 대신 비공개(`exported="false"`) 컴포넌트에 명시적 인텐트로 한다

### 막는 공격

| 공격 | 이 라이브러리가 하는 일 |
|---|---|
| **인텐트 리다이렉션** — extras 에 실린 Intent·PendingIntent 를 앱이 그대로 `startActivity` 해 비공개 컴포넌트가 열림 | `readLaunch`·`readNewIntent` 는 data URI 와 발신자만 꺼내고 extras 값은 읽지 않는다. extras 의 Intent·Parcelable·Bundle·Uri 를 꺼내는 API 가 아예 없고, `stringExtra` 등은 원시 타입만 읽는다. 셀렉터가 붙은 인텐트와 구조가 손상된 extras 는 `Unsafe` 로 거른다. 링크를 다른 링크로 바꿔 넘기는 인터셉터 API 도 없다 |
| **`intent://` 실행** — 웹 페이지·채팅 링크의 `intent:` URI 를 풀어 그대로 실행 | `externalIntent`·`openExternal` 은 https 와 허용 호스트만 받고, 컴포넌트·패키지·셀렉터·extras 없는 BROWSABLE 인텐트만 만든다 |
| **오픈 리다이렉트** — `?next=https://evil.com` 같은 값으로 외부로 보냄 | `Param.httpsUrl` 이 값을 다시 엄격히 파싱해 https·포트 없음·허용 호스트와 **정확히 일치**할 때만 통과시킨다. `user@host`·`//host`·`\`·`example.com.evil.com`·켈빈 기호(`K`) 같은 유니코드 대소문자 접기는 모두 거부된다. 이동할 때는 `toUriString()` 의 정규형을 쓴다 |
| **발신자 위장** — 외부 앱이 `EXTRA_REFERRER` 나 자기 패키지 이름(`getBasePackageName`)을 바꿔 자기 앱·제휴 앱인 척함 | referrer 는 어느 경로로 왔든 `CLAIMED` 로만 보고 신뢰를 올리지 않는다. SELF 는 서명 토큰, FIRST_PARTY 는 OS 가 uid 로 보증한 패키지의 서명 대조로만 준다([신뢰 모델](#신뢰-모델)). https 라는 사실만으로 신뢰를 올리지 않는다 |
| **경로 조작** — 경로 조각에 구분자·점·인코딩을 끼움 | `%2F`·`%5C`(새 경로 조각처럼 해석될 값), `.`·`..`, 이중 인코딩(디코딩 뒤 `%` 가 남음 — `%252F`·`%25٢E` 등 비 ASCII 숫자 포함), NFKC 정규형이 아닌 값(전각 `．．`·`／`, 조합 문자)을 거부한다. 16진 숫자는 ASCII 만 받는다 |
| **파라미터 주입** — 선언하지 않은 쿼리, 중복 이름, 제어 문자를 끼운 값 | 선언한 이름만 결과에 남고(또는 `REJECT`), 선언한 이름의 중복은 거부한다. 잘못된 UTF-8·짝 없는 서로게이트, 제어 문자·양방향 서식 문자(`U+202E`)·폭 없는 문자, 사용자 정보, 비 ASCII 호스트(IDN 은 punycode 만), 정규형이 아닌 IPv4 꼴 호스트(`0x7f.1`·`127.1`), 앞자리 0 포트를 거부한다. 모든 길이에 상한이 있다. 쿼리 **값**은 구분자·`\`·정규화를 검사하지 않는다 — 값의 형식은 `Param` 검증기(`pattern`)가 정한다 |
| **검증 우회 객체** | `ResolvedDeepLink` 는 생성자가 공개되지 않고 `copy` 도 없다. 신뢰 수준을 바꿔 낀 링크를 만들 수 없고, `resume` 은 자기가 만든 링크만 받는다 |
| **인텐트 폭주** — 다른 앱이 링크를 끝없이 보내 메모리를 채우거나 복귀 때 화면을 수백 번 움직임 | 대기열은 상한(기본 8)이 있고 넘치면 가장 오래된 링크를 버린다 |
| **로그 유출** | 라이브러리는 로그를 쓰지 않는다. 모든 결과·거부 사유·요청의 `toString` 은 URI 원문·파라미터 값을 담지 않는다(`DeepLinkUri.redacted()` 는 경로 조각도 지운다). 검증기·규칙 예외도 메시지 대신 타입 이름만 싣는다 |

### 앱이 직접 해야 하는 일

- **App Links**: https 링크를 쓰면 `https://<도메인>/.well-known/assetlinks.json` 을 배포하고 인텐트 필터에
  `android:autoVerify="true"` 를 단다. 검증 결과는 [`appLinkStatus`](#app-links-검증-상태)(API 31+)로 확인한다.
  그래도 아무 앱이나 같은 URI 로 인텐트를 직접 보낼 수 있으므로 https 진입은 기본 `PUBLIC` 이다
- **exported Activity 위생**: 딥링크 진입 Activity 하나만 `exported="true"` 로 두고 나머지는 `false`.
  진입 Activity 는 받은 인텐트를 되넘기지 않는다(자기 링크 토큰이 든 인텐트도 다른 앱에 넘기지 않는다)
- **패키지 가시성**: Android 11+ 에서 `TrustedCaller` 의 서명을 읽으려면 앱 매니페스트에
  `<queries><package android:name="…"/></queries>` 를 적는다. 라이브러리는 매니페스트에 아무것도 더하지 않는다. 조회가 실패하면 그 앱은 `OS_VERIFIED` 에 머문다(안전한 쪽으로 실패)
- **서명 인증서**: Play 앱 서명을 쓰는 제휴 앱이면 업로드 키가 아니라 **앱 서명 키** 의 SHA-256 을 적는다.
  키를 교체(v3/v3.1)했다면 새 인증서는 `sha256Digests`, 옛 인증서는 `previousSha256Digests` 에 적고
  `rotationMinSdk` 를 apksigner `--rotation-min-sdk-version`(기본 33)과 같게 둔다. 옛 인증서는 그 API 미만
  기기에서만 받는다 — 그 기기들은 플랫폼이 옛 키를 현재 키로 보고하기 때문이고, 옛 키가 유출됐다면 그 기기에서는 막지 못한다
- **민감한 동작**: 결제·계정 변경·삭제는 딥링크 파라미터만으로 실행하지 않는다. 앱 안에서 다시 확인·인증한다
- **사용자 표시**: 거부 사유를 사용자에게 그대로 보이지 않는다("열 수 없는 링크" 정도)
- **값 정규화**: 라이브러리는 값을 정규화하지 않는다. 쿼리 값을 파일 이름·URL 로 조립하면 `pattern` 으로 ASCII 를 강제한다
- **`pattern` 정규식**: Android 는 JDK 가 아니라 ICU 정규식이다. 짝 없는 `}`·`]` 는 기기에서만 `PatternSyntaxException`, `\d`·`\w`·`\s` 는 기기에서 유니코드 숫자·글자·공백까지 맞춘다. `[0-9]` 처럼 명시하고 괄호는 이스케이프한다
- 매니페스트 인텐트 필터의 스킴·호스트와 등록한 경로를 맞춘다

## API 레벨별 동작

minSdk 23, compileSdk 36. 분기는 `@RequiresApi` 를 단 작은 함수로 격리했다.

| 동작 | 23–27 | 28–29 | 30 | 31–32 | 33 | 34+ |
|---|---|---|---|---|---|---|
| 제휴 앱 서명 확인 | `signatures` | `SigningInfo` 현재 서명자 | 같음 + `<queries>` 필요 | 같음 | 같음 | 같음 |
| 발신자 OS 보증(`OS_VERIFIED`·FIRST_PARTY) | 없음 | 없음 | 없음 | 없음 | 없음 | 처음 실행에서 `launchedFromUid` 대조 |
| extras 역직렬화(`Unsafe(UNREADABLE_EXTRAS)` 범위) | 처음 닿을 때 전체 — 알 수 없는 클래스도 걸림 | 같음 | 같음 | 같음 | 읽은 키만 — 구조 손상만 걸림 | 같음 |
| `appLinkStatus()` | `Unsupported` | `Unsupported` | `Unsupported` | `DomainVerificationManager` | 같음 | 같음 |
| `appLinkSettingsIntent()` | 앱 상세 설정 | 앱 상세 설정 | 앱 상세 설정 | "기본으로 열기" 설정 | 같음 | 같음 |
| `selfPendingIntent` | `FLAG_IMMUTABLE` 강제 | 같음 | 같음 | 같음(`FLAG_MUTABLE` 거부) | 같음 | 같음 |

## 알려진 한계

- 참고로 남기는 `CLAIMED` 패키지 이름은 위조될 수 있다. 분석에 쓰더라도 판단에 쓰지 않는다. "○○ 앱에서 열림" 처럼 화면에 보여 주는 데도 쓰지 않는다 — 받는 앱 자신의 이름이 나오기도 한다. 커스텀 `TrustPolicy` 도 이 값으로 신뢰를 올리지 않는다
- `OS_VERIFIED` 는 PendingIntent 의 경우 **만든 앱**을 가리킨다. 제휴 앱이 만든 PendingIntent 를 제3의 앱이 보내면 제휴 앱으로 보인다
- **extras 역직렬화(API 33+)**: 플랫폼이 Parcelable·Serializable 값을 꺼낼 때까지 역직렬화하지 않으므로, 리더는 알 수 없는
  클래스가 든 extras 를 감지하지 **않는다**(`Unsafe(UNREADABLE_EXTRAS)` 는 구조가 손상된 경우만). 리더는 그 값을 꺼내지
  않으니 그대로 두면 무해하다. `stringExtra` 등으로 그 키를 읽으면 타입 확인 전에 역직렬화된다. API 23~32 는 extras 에 처음
  닿을 때 플랫폼이 전부 역직렬화하며(플랫폼의 `getReferrer()` 도 그렇다), 이때 알 수 없는 클래스는 `Unsafe` 로 걸린다
- 자기 링크 토큰은 만료가 없다(알림은 며칠 뒤에도 눌린다). 토큰이 든 인텐트를 다른 앱에 넘기면 그 URI 를 SELF 로 재생할 수 있다.
  키 파일을 지우면(앱 데이터 삭제) 이미 만든 알림의 링크는 `PUBLIC` 으로 떨어진다
- 멀티 프로세스 앱에서 두 프로세스가 동시에 처음 서명하면 드물게 한쪽 토큰이 맞지 않아 `PUBLIC` 이 될 수 있다(안전한 쪽)
- 대기열의 링크에는 유효 시간이 없다. 프로세스가 살아 있으면 오래 전에 온 링크도 복귀할 때 처리된다. 계정이 바뀌면 `clearPending()` 으로 비운다
- 대기열은 상한을 넘으면 오래된 링크를 버리므로, 다른 앱이 폭주시키면 그 사이에 온 자기 링크(알림 탭 등)도 밀려날 수 있다
- `intent://…#Intent;…;end` 로 들어온 링크는 프래그먼트를 버리므로 `intent` 스킴을 등록해도 Intent 파라미터를 읽을 수 없다. 받지 않는 것을 권한다
- 서명 확인과 uid 대조는 PackageManager 를 믿는다. 루팅된 기기는 위협 모델 밖이다
- `appLinkStatus()` 는 API 31 미만에서 `Unsupported` 다 — 그 아래에는 검증 결과를 묻는 공개 API 가 없다. 리플렉션·숨은 설정으로 우회하지 않는다
- `externalIntent`·`openExternal` 은 https 만 연다. `http`·`mailto:`·`tel:`·`market:` 는 이 함수의 범위가 아니다(앱이 직접 만든다). 프래그먼트(`#…`)는 버린다
- Compose 효과·Navigation 연동은 라이브러리에 없다. README 의 코드를 앱에 복사해서 쓴다

## 스레드

- `resolve`·`externalIntent`·`appLinkStatus` 는 어느 스레드에서든 부를 수 있다
- suspend `handle`·`resume` 은 스레드를 바꾸지 않는다. 라우터가 화면을 움직이면 메인 스레드에서 부른다. 콜백 `handle`·`resume` 은 어디서 불러도 메인 스레드에서 돌고 결과도 메인 스레드로 온다
- `offer`·`clearPending`·`pendingCount` 는 어느 스레드에서든 부를 수 있다. `consumeWhileResumed` 와 그 결과의 `close` 는 메인 스레드에서 부르고, 라우터·결과 콜백은 메인 스레드에서 돈다
- `readLaunch`·`readNewIntent`·`offerLaunch`·`offerNewIntent`·`CallerVerifier.identify`·`openExternal` 은 메인 스레드에서 부른다(PackageManager 조회가 짧게 블로킹한다)
- `selfIntent`·`selfPendingIntent` 와 첫 링크 확인은 키 파일을 한 번 읽거나 만든다(짧은 디스크 I/O, 이후 메모리 캐시)
- 설정(`DeepLinkConfig`)과 `DeepLinks` 는 불변이며 동시에 여러 링크를 처리해도 안전하다

## 최소 요구사항

- minSdk 23, compileSdk 36. 런타임 의존은 `androidx.lifecycle:lifecycle-common`(api — `LifecycleOwner` 가 공개 API 에 나온다)·`kotlinx-coroutines-android`(implementation) 다. 내비게이션·Compose·DI 라이브러리에는 의존하지 않는다
- 라이브러리는 매니페스트에 권한·컴포넌트·`<queries>` 를 더하지 않는다. ProGuard 규칙은 aar 의 `consumer-rules.pro` 에 있어 앱이 따로 적지 않는다(확인용 앱 `sample` 을 R8 release 로 빌드해 본다)

## 버전

SemVer. 태그는 `deeplink-v<버전>`. 변경 내역은 [CHANGELOG.md](CHANGELOG.md), 예외와 실패 신호는 [ERRORS.md](ERRORS.md).

빌드 조건: `./gradlew build` 가 BCV `apiCheck`(공개 API 고정), Robolectric 테스트(API 23·27·28·31·33·34·35 등),
Kover `koverVerify`(라인 커버리지 90% 이상), lint, 확인용 앱의 병합 매니페스트 검사와 R8 release 빌드를 포함한다.

## 라이선스

Apache License 2.0. [LICENSE](LICENSE) 참고.
