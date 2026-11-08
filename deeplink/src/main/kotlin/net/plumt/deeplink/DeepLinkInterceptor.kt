package net.plumt.deeplink

/**
 * 검증을 통과한 딥링크가 라우터에 닿기 전에 끼어드는 훅. OkHttp 인터셉터와 같은 사슬 구조다.
 * [DeepLinkConfig.Builder.interceptor] 로 등록한다.
 *
 * 할 수 있는 일:
 * - `chain.proceed()` 로 넘기고 결과를 그대로(또는 관찰한 뒤) 돌려준다 — 분석·계측
 * - `DeepLinkOutcome.Deferred(chain.link, "login")` 을 돌려 미룬다 — 로그인 후 [DeepLinks.resume]
 * - `DeepLinkOutcome.Rejected(Rejection.Intercepted("..."))` 로 막는다 — 강제 업데이트 게이트
 *
 * 다른 링크로 바꿔 넘길 수는 없다. 다른 곳으로 보내려면 그 URI 를 [DeepLinks] 로 다시 검증한다.
 * 동기 판정으로 충분하면 [DeepLinkRule] 이 낫다 — [DeepLinks.resolve] 에도 적용된다.
 */
public interface DeepLinkInterceptor {

    /** 작을수록 먼저 실행된다. 같으면 등록 순서. 기본 0. */
    public val priority: Int get() = 0

    /** 링크 하나를 가로챈다. 넘기려면 [DeepLinkChain.proceed] 를 한 번 부른다. */
    public suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome
}

/** 인터셉터 사슬의 현재 위치. */
public interface DeepLinkChain {

    /** 처리 중인 링크. */
    public val link: ResolvedDeepLink

    /**
     * 다음 인터셉터(없으면 라우터)로 넘긴다. 한 사슬 위치에서 한 번만 부를 수 있다.
     *
     * @throws IllegalStateException 두 번째로 불렀을 때(이동이 두 번 일어나는 것을 막는다)
     */
    public suspend fun proceed(): DeepLinkOutcome
}

/**
 * 검증·인터셉터를 통과한 링크를 화면으로 옮긴다. Navigation Compose·Fragment·Activity 무엇이든 구현할 수 있다.
 * 화면마다 다르므로 설정에 두지 않고 [DeepLinks.handle]·[DeepLinks.consumeWhileResumed] 에 넘긴다.
 */
public fun interface DeepLinkRouter {
    /** 처리했으면 `true`, 처리할 화면이 없으면 `false`. 호출 스레드에서 그대로 불린다. */
    public suspend fun route(link: ResolvedDeepLink): Boolean
}

/**
 * 모든 처리 결과를 받는 관찰자. 분석·로그를 여기서 붙인다([DeepLinkConfig.Builder.listener]).
 *
 * 결과 객체의 [toString] 은 URI 원문·파라미터 값을 담지 않으므로 그대로 로그에 써도 된다.
 * 이 훅이 던진 예외는 삼키지 않고 [DeepLinks.handle] 호출부로 전파된다.
 */
public fun interface DeepLinkListener {
    /** [outcome] 하나를 관찰한다. */
    public fun onOutcome(outcome: DeepLinkOutcome)
}

/**
 * 앱이 더하는 검증 규칙. 경로·신뢰·파라미터 검증을 모두 통과한 링크에 등록 순서대로 적용한다([DeepLinkConfig.Builder.rule]).
 * 인터셉터와 달리 [DeepLinks.resolve] 에도 적용되고, 거부하면 링크가 만들어지지 않는다.
 *
 * 예: 두 파라미터의 조합 검사, 특정 경로를 원격 설정으로 끄기, 기간이 지난 캠페인 경로 막기.
 *
 * 예외를 던지면 그 링크는 `Rejection.Custom("rule_exception", <예외 타입 이름>)` 으로 거부된다(`Error` 는 전파).
 */
public fun interface DeepLinkRule {
    /** 통과면 `null`, 거부면 사유. 보통 [Rejection.Custom] 을 돌려준다. 사유에 값을 담지 않는다. */
    public fun check(link: ResolvedDeepLink): Rejection?
}
