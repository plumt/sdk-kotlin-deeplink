# sdk-kotlin-deeplink

딥링크를 **허용 목록으로만** 받아들이는 Kotlin·Android 라이브러리.
URI 를 엄격하게 분해하고, 경로 템플릿과 타입 있는 파라미터로 검증하고, 발신자를 플랫폼이 보증한 값으로만
판정한 뒤, 인터셉터 사슬을 거쳐 소비자가 고른 라우터(Navigation Compose 든 무엇이든)로 넘긴다.

커스텀 스킴(`myapp://`)은 어떤 앱이든 보낼 수 있고, https App Link 도 아무 앱이나 같은 URI 로 인텐트를
직접 보낼 수 있다. 그래서 기본값은 전부 **거부**다. 등록하지 않은 스킴·호스트·경로·파라미터는
이유([`Rejection`](deeplink-core/src/main/kotlin/net/plumt/deeplink/Rejection.kt))와 함께 거부된다.

| 모듈 | 좌표 | 형태 | 내용 |
|---|---|---|---|
| `deeplink-core` | `net.plumt.deeplink:deeplink-core:0.0.2` | jar (순수 Kotlin) | URI 파서·경로 매칭·파라미터 검증·신뢰 정책·인터셉터·라우터 포트 |
| `deeplink` | `net.plumt.deeplink:deeplink:0.0.2` | aar | 인텐트 해석·발신자 확인(OS 보증 uid·서명·자기 링크 토큰)·extras 정제·자기 전용 인텐트·앞 화면에서 한 번만 처리하는 대기열 |

`deeplink` 는 `deeplink-core` 를 `api` 로 노출하므로 Android 앱은 `deeplink` 하나만 넣으면 된다.

## 설치

```kotlin
dependencies {
    implementation("net.plumt.deeplink:deeplink:0.0.2")        // Android
    // implementation("net.plumt.deeplink:deeplink-core:0.0.2") // 서버·데스크톱·멀티 모듈의 도메인 계층
}
```

## 최소 요구사항

- `deeplink-core`: JVM 11 바이트코드. 런타임 의존은 Kotlin 표준 라이브러리뿐이다(코루틴 라이브러리도 쓰지 않는다)
- `deeplink`: minSdk 23, compileSdk 36. 서명 확인은 API 28+ 에서 `SigningInfo`, 그 아래는 `signatures` 로 분기한다.
  다른 앱을 FIRST_PARTY 로 올리는 근거(OS 가 uid 로 보증한 발신자)는 Android 14(API 34)+ 에만 있다 — [신뢰 모델](#신뢰-모델).
  런타임 의존은 `deeplink-core`·`androidx.lifecycle:lifecycle-common`·`kotlinx-coroutines-core` 다. 내비게이션 라이브러리에는 의존하지 않는다
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

`onCreate`·`onNewIntent` 는 링크를 **대기열에 넣기만** 하고, 처리는 앞에 선(RESUMED) 화면이 가져가서 한다.
이렇게 하면 콜드 스타트 링크가 화면 준비 전에 사라지지 않고, Activity 인스턴스가 여럿이어도 한 번만 처리되고,
뒤에 깔린 인스턴스가 보이지 않는 화면을 움직이지 않는다.

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

```kotlin
// 프로세스에 하나. Activity 필드에 두지 않는다 — 인스턴스·회전을 건너 살아야 한다
object AppLinks {
    val queue = DeepLinkQueue()
}

class MainActivity : ComponentActivity() {
    private val reader by lazy {
        DeepLinkIntentReader.create(
            this,
            trustedCallers = listOf(TrustedCaller("com.example.partner", setOf("AB:CD:…"))),
        )
    }
    private val handler by lazy {
        deepLinkHandler {
            route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
            trustPolicy(AndroidDeepLinks.trustPolicy(this@MainActivity))
            router { link -> showItem(link[itemId]); true }   // 이 인스턴스의 화면을 움직인다
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        enqueue(reader.readLaunch(this, savedInstanceState))     // 재생성(회전·복원)이면 NotDeepLink
        // RESUMED 인 동안만 가져간다. 파괴되면 스스로 닫힌다
        AppLinks.queue.consumeWhileResumed(this) { request ->
            when (handler.handle(request)) {
                is DeepLinkOutcome.Handled, is DeepLinkOutcome.Deferred -> Unit
                else -> showHome()                               // 거부·실패는 기본 화면
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)                                        // getIntent() 를 최신으로. 판정에는 쓰이지 않는다
        enqueue(reader.readNewIntent(this, intent))              // 처음 띄운 앱 정보를 이 링크에 붙이지 않는다
    }

    private fun enqueue(result: IntentReadResult) {
        // NotDeepLink·Unsafe 는 평소 시작 흐름
        if (result is IntentReadResult.Link) AppLinks.queue.offer(result.request)
    }
}
```

`readLaunch`·`readNewIntent` 를 구분하는 것은 그대로 지킨다. 대기열은 받은 `DeepLinkRequest` 객체를 그대로 넘기므로
발신자 판정이 큐에 머무는 동안 바뀌지 않고, 다른 링크의 신뢰가 옮겨 붙지도 않는다.

### Compose·Navigation 에서

라이브러리는 `NavController` 를 모른다. 처리기의 라우터가 그 인스턴스의 컨트롤러를 쓰면 된다. 소비자는 효과로
붙인다 — `NavHost` 가 첫 구성에서 그래프를 정한 **뒤에** 붙으므로, `onCreate` 에서 붙일 때와 달리 컨트롤러가 준비되지 않은 채
링크를 가져가지 않는다. 그 전까지 링크는 큐에 남는다.

```kotlin
@Composable
fun DeepLinkEffect(queue: DeepLinkQueue, handle: suspend (DeepLinkRequest) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latestHandle by rememberUpdatedState(handle)
    DisposableEffect(queue, owner) {
        val consumer = queue.consumeWhileResumed(owner) { latestHandle(it) }
        onDispose { consumer.close() }
    }
}

class MainActivity : ComponentActivity() {
    private val reader by lazy { DeepLinkIntentReader.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enqueue(reader.readLaunch(this, savedInstanceState))
        setContent {
            val nav = rememberNavController()
            val handler = remember(nav) {
                deepLinkHandler {
                    route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
                    route(deepLinkRoute("search", "myapp://search"))
                    trustPolicy(AndroidDeepLinks.trustPolicy(this@MainActivity))
                    router { link ->
                        when (link.route.id) {
                            "item" -> nav.navigate("item/${link[itemId]}")
                            "search" -> nav.navigateToTab("search", tabs)
                        }
                        true
                    }
                }
            }
            AppScaffold(nav)                                       // NavHost·탭 바
            DeepLinkEffect(AppLinks.queue) { handler.handle(it) }  // NavHost 바깥에서
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueue(reader.readNewIntent(this, intent))
    }

    private fun enqueue(result: IntentReadResult) {
        if (result is IntentReadResult.Link) AppLinks.queue.offer(result.request)
    }
}
```

주의:

- `DeepLinkEffect` 는 **`NavHost` 목적지 안에 두지 않는다.** 거기서 `LocalLifecycleOwner` 는 그 목적지이므로 다른 화면으로 가면 소비가 멈춘다
- **`composable(deepLinks = …)`·`navDeepLink` 를 쓰지 않는다.** `NavController` 가 그래프를 정할 때 `activity.intent` 를 직접 처리해 이 라이브러리의 검증을 건너뛰고, 같은 링크가 두 번 열린다
- 화면이 링크를 받을 준비가 안 됐으면(로그인 화면 등) 준비될 때까지 `DeepLinkEffect` 를 그리지 않는다. 링크는 큐에 남는다. 버려야 하면 `AppLinks.queue.clear()`
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

- **`singleTop` 이어도 인스턴스가 하나라고 보장되지는 않는다.** 맨 위가 다른 Activity 이거나, 발신 앱이 `FLAG_ACTIVITY_NEW_TASK` 없이 보내 발신 앱 태스크에 들어오면 새 인스턴스가 생긴다. 그래서 큐를 프로세스 전역에 두고 RESUMED 인 쪽만 소비한다
- `singleTop`·`singleTask` 에서는 **`onNewIntent` 를 재정의하지 않으면 링크가 그대로 사라진다.** 플랫폼은 새 인텐트를 `getIntent()` 에 넣어 주지 않으므로 필요하면 `setIntent(intent)` 를 부르되, 처리 대상은 매개변수 `intent` 다
- `onNewIntent` 로 온 링크는 `readNewIntent` 라서 `OS_VERIFIED`·`FIRST_PARTY` 가 나오지 않는다([신뢰 모델](#신뢰-모델)). 제휴 앱 링크가 앱이 떠 있을 때도 FIRST_PARTY 여야 하면 별도의 `standard` 진입 Activity 가 필요하다

### 대기열의 동작

| 상황 | 동작 |
|---|---|
| 소비자가 없거나 RESUMED 가 아님 | 큐에 남는다. 처음 RESUMED 인 소비자가 가져간다 |
| 인스턴스가 여럿, 또는 분할 화면으로 둘 다 RESUMED | 링크마다 먼저 가져간 소비자 하나만 처리한다 |
| 회전·재생성 | 처리 끝난 링크는 큐에 없고 `readLaunch` 는 `NotDeepLink` 라 다시 처리되지 않는다. 최근 앱 화면에서 다시 연 실행도 `NotDeepLink` 다 |
| 처리 중 PAUSE | **취소하지 않고 보류한다.** 메인 스레드 코드는 RESUMED 일 때만 이어지므로 복귀하면 이어서 끝나고, 멈춘 화면이 라우터를 부르지 않는다 |
| 처리 중 파괴·`close()` | `handle` 을 취소하고 같은 요청을 맨 앞에 되돌려 다음 소비자가 처음부터 처리한다. `isFinishing` 인 Activity 면 버린다. 다시 처리되므로 라우터를 부른 뒤에는 suspend 하지 않는다 |
| 큐가 가득 참(기본 8개) | 가장 오래된 링크를 버리고 `offer` 가 그 링크를 돌려준다. 쌓인 링크는 복귀할 때 차례로 처리되므로 값을 크게 잡지 않는다 |
| 프로세스 종료 | 함께 사라진다. 디스크에 쓰지 않는다. 처음 띄운 인텐트는 플랫폼이 다시 주지만 저장된 상태가 있으면 처리한 것으로 본다 |

### 로그인 후 이어 가기

```kotlin
val authInterceptor = object : DeepLinkInterceptor {
    override val priority = -10
    override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
        if (session.isLoggedIn || chain.link.route.id == "home") chain.proceed()
        else DeepLinkOutcome.Deferred(chain.link, "login")
}

// handle 결과가 Deferred 면 link 를 메모리(ViewModel)에 두고, 로그인 뒤
handler.resume(deferred.link)   // 다시 파싱하지 않고 사슬을 처음부터 돈다. 링크를 만든 그 처리기로 부른다

// 검증한 next 로 이동할 때는 원래 문자열을 다른 파서로 다시 읽지 말고 정규형을 쓴다
openInBrowser(link[next].toUriString())
```

`resume` 은 링크를 만든 처리기만 받으므로, 재개가 회전을 건너가야 하면 처리기를 인스턴스 밖(ViewModel 등)에 두고
라우터가 지금 앞에 선 인스턴스의 화면을 찾게 한다. 인터셉터가 끝나지 않으면 그 소비자의 다음 링크가 기다리므로, 오래 걸리는 조건은
`Deferred` 로 돌려준다.

### 자기 앱에 보내는 링크(알림)

```kotlin
val pending = AndroidDeepLinks.selfPendingIntent(context, "myapp://notice", requestCode = 1)
// 패키지 고정 + FLAG_IMMUTABLE + 이 앱만 만들 수 있는 서명 토큰. FLAG_MUTABLE 은 거부한다
```

`selfIntent`·`selfPendingIntent` 로 만든 링크만 `SELF` 가 된다. 이 함수에 **외부에서 온 문자열**(채팅 메시지·
웹 페이지·다른 사용자가 고른 링크)을 넣지 않는다 — 넣으면 그 링크가 SELF 경로를 연다. 그런 링크는
`startActivity(Intent(ACTION_VIEW, uri))` 로 그대로 열면 된다. 같은 앱이 띄웠더라도 토큰이 없으면 `PUBLIC` 이다.

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

### 신뢰 모델

신뢰 수준(`TrustLevel`)은 "발신 앱이 무엇이라고 주장하는가" 가 아니라 **플랫폼이나 앱 자신의 비밀이 무엇을
보증하는가** 로만 정한다. 기본 정책 `TrustPolicy.standard(selfPackage)`:

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
| **인텐트 리다이렉션** — extras 에 실린 Intent·PendingIntent 를 앱이 그대로 `startActivity` 해 비공개 컴포넌트가 열림 | `DeepLinkIntentReader` 는 data URI 와 발신자만 꺼내고 extras 값은 읽지 않는다. extras 의 Intent·Parcelable·Bundle·Uri 를 꺼내는 API 가 아예 없고, `SafeExtras` 는 문자열·정수·불리언만 읽는다. 셀렉터가 붙은 인텐트와 구조가 손상된 extras 는 `Unsafe` 로 거른다. 링크를 다른 링크로 바꿔 넘기는 인터셉터 API 도 없다 |
| **오픈 리다이렉트** — `?next=https://evil.com` 같은 값으로 외부로 보냄 | `Param.httpsUrl` 이 값을 다시 엄격히 파싱해 https·포트 없음·허용 호스트와 **정확히 일치**할 때만 통과시킨다. `user@host`·`//host`·`\`·`example.com.evil.com`·켈빈 기호(`K`) 같은 유니코드 대소문자 접기는 모두 거부된다. 이동할 때는 `toUriString()` 의 정규형을 쓴다 |
| **발신자 위장** — 외부 앱이 `EXTRA_REFERRER` 나 자기 패키지 이름(`getBasePackageName`)을 바꿔 자기 앱·제휴 앱인 척함 | referrer 는 어느 경로로 왔든 `CLAIMED` 로만 보고 신뢰를 올리지 않는다. SELF 는 서명 토큰, FIRST_PARTY 는 OS 가 uid 로 보증한 패키지의 서명 대조로만 준다([신뢰 모델](#신뢰-모델)). https 라는 사실만으로 신뢰를 올리지 않는다 |
| **경로 조작** — 경로 조각에 구분자·점·인코딩을 끼움 | `%2F`·`%5C`(새 경로 조각처럼 해석될 값), `.`·`..`, 이중 인코딩(디코딩 뒤 `%` 가 남음 — `%252F`·`%25٢E` 등 비 ASCII 숫자 포함), NFKC 정규형이 아닌 값(전각 `．．`·`／`, 조합 문자)을 거부한다. 16진 숫자는 ASCII 만 받는다 |
| **파라미터 주입** — 선언하지 않은 쿼리, 중복 이름, 제어 문자를 끼운 값 | 선언한 이름만 결과에 남고(또는 `REJECT`), 선언한 이름의 중복은 거부한다. 잘못된 UTF-8·짝 없는 서로게이트, 제어 문자·양방향 서식 문자(`U+202E`)·폭 없는 문자, 사용자 정보, 비 ASCII 호스트(IDN 은 punycode 만), 정규형이 아닌 IPv4 꼴 호스트(`0x7f.1`·`127.1`), 앞자리 0 포트를 거부한다. 모든 길이에 상한이 있다. 쿼리 **값**은 구분자·`\`·정규화를 검사하지 않는다 — 값의 형식은 `Param` 검증기(`pattern`)가 정한다 |
| **검증 우회 객체** | `ResolvedDeepLink` 는 생성자가 공개되지 않고 `copy` 도 없다. 신뢰 수준을 바꿔 낀 링크를 만들 수 없고, `resume` 은 자기가 만든 링크만 받는다 |
| **인텐트 폭주** — 다른 앱이 링크를 끝없이 보내 메모리를 채우거나 복귀 때 화면을 수백 번 움직임 | `DeepLinkQueue` 는 대기 수에 상한(기본 8)이 있고 넘치면 가장 오래된 링크를 버린다 |
| **로그 유출** | 라이브러리는 로그를 쓰지 않는다. 모든 결과·거부 사유·요청의 `toString` 은 URI 원문·파라미터 값을 담지 않는다(`DeepLinkUri.redacted()` 는 경로 조각도 지운다). 검증기 예외도 메시지 대신 타입 이름만 싣는다 |

### 앱이 직접 해야 하는 일

- **App Links**: https 링크를 쓰면 `https://<도메인>/.well-known/assetlinks.json` 을 배포하고 인텐트 필터에
  `android:autoVerify="true"` 를 단다. 그래도 아무 앱이나 같은 URI 로 인텐트를 직접 보낼 수 있으므로,
  이 라이브러리는 https 진입을 기본 `PUBLIC` 으로 본다
- **exported Activity 위생**: 딥링크 진입 Activity 하나만 `exported="true"` 로 두고 나머지는 `false`.
  진입 Activity 는 받은 인텐트를 되넘기지 않는다(자기 링크 토큰이 든 인텐트도 다른 앱에 넘기지 않는다)
- **패키지 가시성**: Android 11+ 에서 `TrustedCaller` 의 서명을 읽으려면 매니페스트에
  `<queries><package android:name="…"/></queries>` 를 적는다. 조회가 실패하면 그 앱은 `OS_VERIFIED` 에 머문다(안전한 쪽으로 실패)
- **서명 인증서**: Play 앱 서명을 쓰는 제휴 앱이면 업로드 키가 아니라 **앱 서명 키** 의 SHA-256 을 적는다.
  키를 교체(v3/v3.1)했다면 새 인증서는 `sha256Digests`, 옛 인증서는 `previousSha256Digests` 에 적고
  `rotationMinSdk` 를 apksigner `--rotation-min-sdk-version`(기본 33)과 같게 둔다. 옛 인증서는 그 API 미만
  기기에서만 받는다 — 그 기기들은 플랫폼이 옛 키를 현재 키로 보고하기 때문이고, 옛 키가 유출됐다면 그 기기에서는 막지 못한다
- **민감한 동작**: 결제·계정 변경·삭제는 딥링크 파라미터만으로 실행하지 않는다. 앱 안에서 다시 확인·인증한다
- **사용자 표시**: 거부 사유를 사용자에게 그대로 보이지 않는다("열 수 없는 링크" 정도)
- **값 정규화**: 라이브러리는 값을 정규화하지 않는다. 쿼리 값을 파일 이름·URL 로 조립하면 `pattern` 으로 ASCII 를 강제한다
- **`pattern` 정규식**: Android 는 JDK 가 아니라 ICU 정규식이다. 짝 없는 `}`·`]` 는 기기에서만 `PatternSyntaxException`, `\d`·`\w`·`\s` 는 기기에서 유니코드 숫자·글자·공백까지 맞춘다. `[0-9]` 처럼 명시하고 괄호는 이스케이프한다
- 매니페스트 인텐트 필터의 스킴·호스트와 등록한 경로를 맞춘다

### 한계

- 참고로 남기는 `CLAIMED` 패키지 이름은 위조될 수 있다. 분석에 쓰더라도 판단에 쓰지 않는다. "○○ 앱에서 열림" 처럼 화면에 보여 주는 데도 쓰지 않는다 — 받는 앱 자신의 이름이 나오기도 한다. 커스텀 `TrustPolicy` 도 이 값으로 신뢰를 올리지 않는다
- `OS_VERIFIED` 는 PendingIntent 의 경우 **만든 앱**을 가리킨다. 제휴 앱이 만든 PendingIntent 를 제3의 앱이 보내면 제휴 앱으로 보인다
- **extras 역직렬화(API 33+)**: 플랫폼이 Parcelable·Serializable 값을 꺼낼 때까지 역직렬화하지 않으므로, 리더는 알 수 없는
  클래스가 든 extras 를 감지하지 **않는다**(`Unsafe(UNREADABLE_EXTRAS)` 는 구조가 손상된 경우만). 리더는 그 값을 꺼내지
  않으니 그대로 두면 무해하다. `SafeExtras` 로 그 키를 읽으면 타입 확인 전에 역직렬화된다. API 23~32 는 extras 에 처음
  닿을 때 플랫폼이 전부 역직렬화하며(플랫폼의 `getReferrer()` 도 그렇다), 이때 알 수 없는 클래스는 `Unsafe` 로 걸린다
- 자기 링크 토큰은 만료가 없다(알림은 며칠 뒤에도 눌린다). 토큰이 든 인텐트를 다른 앱에 넘기면 그 URI 를 SELF 로 재생할 수 있다.
  키 파일을 지우면(앱 데이터 삭제) 이미 만든 알림의 링크는 `PUBLIC` 으로 떨어진다
- 멀티 프로세스 앱에서 두 프로세스가 동시에 처음 서명하면 드물게 한쪽 토큰이 맞지 않아 `PUBLIC` 이 될 수 있다(안전한 쪽)
- 대기열의 링크에는 유효 시간이 없다. 프로세스가 살아 있으면 오래 전에 온 링크도 복귀할 때 처리된다. 계정이 바뀌면 `DeepLinkQueue.clear()` 로 비운다
- 대기열은 상한을 넘으면 오래된 링크를 버리므로, 다른 앱이 폭주시키면 그 사이에 온 자기 링크(알림 탭 등)도 밀려날 수 있다
- `intent://…#Intent;…;end` 링크는 프래그먼트를 버리므로 `intent` 스킴을 등록해도 Intent 파라미터를 읽을 수 없다. 받지 않는 것을 권한다
- 서명 확인과 uid 대조는 PackageManager 를 믿는다. 루팅된 기기는 위협 모델 밖이다

## 스레드

- `DeepLinkHandler.resolve` 는 블로킹 작업이 없다. 어느 스레드에서든 부를 수 있다
- `handle`·`resume` 은 스레드를 바꾸지 않는다. 라우터가 화면을 움직이면 메인 스레드에서 부른다
- `DeepLinkQueue.offer`·`clear`·`size` 는 어느 스레드에서든 부를 수 있다. `consumeWhileResumed` 와 그 결과의 `close` 는 메인 스레드에서 부르고, 처리 람다는 메인 스레드에서 돈다
- `DeepLinkIntentReader.readLaunch`·`readNewIntent`·`CallerVerifier.identify` 는 메인 스레드에서 부른다(PackageManager 조회가 짧게 블로킹한다)
- `AndroidDeepLinks.selfIntent`·`selfPendingIntent` 와 첫 링크 확인은 키 파일을 한 번 읽거나 만든다(짧은 디스크 I/O, 이후 메모리 캐시)
- 처리기는 불변이며 동시에 여러 링크를 처리해도 안전하다

## 버전

모듈마다 따로 SemVer 를 쓴다. 태그는 `deeplink-core-v<버전>`, `deeplink-v<버전>`. 변경 내역은 [CHANGELOG.md](CHANGELOG.md),
예외와 실패 신호는 [ERRORS.md](ERRORS.md).

빌드 조건: `./gradlew build` 가 BCV `apiCheck`(공개 API 고정), Robolectric 테스트(API 23·27·28·31·32·33·34·35),
Kover `koverVerify`(라인 커버리지 90% 이상)를 포함한다.

## 라이선스

Apache License 2.0. [LICENSE](LICENSE) 참고.
