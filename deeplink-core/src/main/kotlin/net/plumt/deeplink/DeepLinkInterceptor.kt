package net.plumt.deeplink

/**
 * 검증을 통과한 딥링크가 라우터에 닿기 전에 끼어드는 훅. OkHttp 인터셉터와 같은 사슬 구조다.
 *
 * 할 수 있는 일:
 * - `chain.proceed()` 로 넘기고 결과를 그대로(또는 관찰한 뒤) 돌려준다 — 분석·계측
 * - `DeepLinkOutcome.Deferred(chain.link, "login")` 을 돌려 미룬다 — 로그인 후 [DeepLinkHandler.resume]
 * - `DeepLinkOutcome.Rejected(Rejection.Intercepted("..."))` 로 막는다 — 강제 업데이트 게이트
 *
 * 다른 링크로 바꿔 넘길 수는 없다. 다른 곳으로 보내려면 그 URI 를 [DeepLinkHandler] 로 다시 검증한다.
 */
public interface DeepLinkInterceptor {

    /** 작을수록 먼저 실행된다. 같으면 등록 순서. 기본 0. */
    public val priority: Int get() = 0

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

/** 검증·인터셉터를 통과한 링크를 화면으로 옮긴다. Navigation Compose·Fragment·Activity 무엇이든 구현할 수 있다. */
public fun interface DeepLinkRouter {
    /** 처리했으면 `true`, 처리할 화면이 없으면 `false`. 호출 스레드에서 그대로 불린다. */
    public suspend fun route(link: ResolvedDeepLink): Boolean
}

/**
 * 모든 처리 결과를 받는 관찰자. 분석·로그를 여기서 붙인다.
 *
 * 결과 객체의 [toString] 은 URI 원문·파라미터 값을 담지 않으므로 그대로 로그에 써도 된다.
 * 이 훅이 던진 예외는 삼키지 않고 [DeepLinkHandler.handle] 호출부로 전파된다.
 */
public fun interface DeepLinkListener {
    public fun onOutcome(outcome: DeepLinkOutcome)
}
