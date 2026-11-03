package net.plumt.deeplink

import net.plumt.deeplink.internal.DefaultDeepLinkHandler

/**
 * 딥링크 처리 진입점: URI 검증 → 경로 매칭 → 신뢰 판정 → 파라미터 검증 → 인터셉터 사슬 → 라우터.
 *
 * 스레드를 바꾸지 않는다. 라우터가 화면을 움직인다면 메인 스레드에서 [handle] 을 부른다.
 * [resolve] 는 블로킹 작업이 없어 어느 스레드에서 불러도 된다. 인스턴스는 불변이며 스레드 안전하다.
 */
public interface DeepLinkHandler {

    /** 등록된 경로(등록 순서). */
    public val routes: List<DeepLinkRoute>

    /** 검증만 한다. 인터셉터·라우터·리스너는 부르지 않는다. */
    public fun resolve(request: DeepLinkRequest): DeepLinkResolution

    /** 검증하고 인터셉터 사슬과 라우터를 거친다. 결과는 리스너에도 전달된다. */
    public suspend fun handle(request: DeepLinkRequest): DeepLinkOutcome

    /**
     * [DeepLinkOutcome.Deferred] 로 미뤘던 링크를 다시 진행한다. 다시 파싱하지 않고 인터셉터 사슬을
     * 처음부터 돈다(로그인 인터셉터가 이번에는 통과시킨다).
     *
     * @throws IllegalArgumentException [link] 를 이 처리기가 만들지 않았을 때
     */
    public suspend fun resume(link: ResolvedDeepLink): DeepLinkOutcome

    /** [DeepLinkHandler] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        private val routes = ArrayList<DeepLinkRoute>()
        private val interceptors = ArrayList<DeepLinkInterceptor>()
        private val listeners = ArrayList<DeepLinkListener>()
        private var router: DeepLinkRouter? = null
        private var trustPolicy: TrustPolicy = TrustPolicy.standard(null)
        private var uriRules: UriRules = UriRules.DEFAULT

        /** 경로를 등록한다. */
        public fun route(route: DeepLinkRoute): Builder = apply { routes += route }

        /** 경로를 여러 개 등록한다. */
        public fun routes(routes: Iterable<DeepLinkRoute>): Builder = apply { this.routes += routes }

        /** 인터셉터를 등록한다. 실행 순서는 [DeepLinkInterceptor.priority], 같으면 등록 순서. */
        public fun interceptor(interceptor: DeepLinkInterceptor): Builder = apply { interceptors += interceptor }

        /** 결과 관찰자를 등록한다. */
        public fun listener(listener: DeepLinkListener): Builder = apply { listeners += listener }

        /** 라우터(필수). */
        public fun router(router: DeepLinkRouter): Builder = apply { this.router = router }

        /** 신뢰 정책. 기본은 `TrustPolicy.standard(null)` — 자기 앱 판정이 없어 SELF 경로는 열리지 않는다. */
        public fun trustPolicy(policy: TrustPolicy): Builder = apply { trustPolicy = policy }

        /** URI 규칙. 기본 [UriRules.DEFAULT]. */
        public fun uriRules(rules: UriRules): Builder = apply { uriRules = rules }

        /**
         * @throws IllegalArgumentException 경로가 없거나, 경로 id 가 겹치거나, 라우터를 정하지 않았을 때
         */
        public fun build(): DeepLinkHandler {
            require(routes.isNotEmpty()) { "경로를 하나 이상 등록해야 한다" }
            val duplicated = routes.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "경로 id 가 겹친다: $duplicated" }
            val router = requireNotNull(router) { "router 를 정해야 한다" }
            val ordered = interceptors.withIndex().sortedWith(compareBy({ it.value.priority }, { it.index })).map { it.value }
            return DefaultDeepLinkHandler(routes.toList(), ordered, listeners.toList(), router, trustPolicy, uriRules)
        }
    }

    public companion object {
        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** Kotlin DSL: `deepLinkHandler { route(...); router { ... } }` */
public fun deepLinkHandler(block: DeepLinkHandler.Builder.() -> Unit): DeepLinkHandler =
    DeepLinkHandler.builder().apply(block).build()
