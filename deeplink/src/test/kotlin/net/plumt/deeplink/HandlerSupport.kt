package net.plumt.deeplink

/**
 * 설정과 라우터를 한데 묶어 검증 엔진을 부르는 시험용 처리기. 엔진 시험이 라우터를 매번 넘기지 않게 한다.
 * [FakeDeepLinks] 를 거치므로 기본 구현과 같은 내부 처리를 시험한다.
 */
internal class DeepLinkHandler(val links: DeepLinks, private val router: DeepLinkRouter) {
    val routes: List<DeepLinkRoute> get() = links.config.routes

    fun resolve(request: DeepLinkRequest): DeepLinkResolution = links.resolve(request)

    suspend fun handle(request: DeepLinkRequest): DeepLinkOutcome = links.handle(request, router)

    suspend fun resume(link: ResolvedDeepLink): DeepLinkOutcome = links.resume(link, router)

    class Builder {
        private val config = DeepLinkConfig.builder()
        private var router: DeepLinkRouter? = null
        private var policy: TrustPolicy = TrustPolicy.standard(null)

        fun route(route: DeepLinkRoute) = apply { config.route(route) }
        fun routes(routes: Iterable<DeepLinkRoute>) = apply { config.routes(routes) }
        fun interceptor(interceptor: DeepLinkInterceptor) = apply { config.interceptor(interceptor) }
        fun listener(listener: DeepLinkListener) = apply { config.listener(listener) }
        fun rule(rule: DeepLinkRule) = apply { config.rule(rule) }
        fun trustPolicy(policy: TrustPolicy) = apply { this.policy = policy }
        fun uriRules(rules: UriRules) = apply { config.uriRules(rules) }
        fun router(router: DeepLinkRouter) = apply { this.router = router }

        fun build(): DeepLinkHandler {
            val built = config.trustPolicy(policy).build()
            val router = requireNotNull(router) { "router 를 정해야 한다" }
            return DeepLinkHandler(FakeDeepLinks(built), router)
        }
    }

    companion object {
        fun builder() = Builder()
    }
}

internal fun deepLinkHandler(block: DeepLinkHandler.Builder.() -> Unit): DeepLinkHandler =
    DeepLinkHandler.builder().apply(block).build()

/** 시험에서 이름 붙은 인자로 규칙을 만든다. */
internal fun uriRules(
    maxUriLength: Int = 2048,
    maxPathSegments: Int = 16,
    maxSegmentLength: Int = 256,
    maxQueryParameters: Int = 32,
    maxQueryValueLength: Int = 1024,
    allowEncodedSlash: Boolean = false,
    allowDoubleEncoding: Boolean = false,
    allowUnnormalized: Boolean = false,
): UriRules = UriRules.builder()
    .maxUriLength(maxUriLength)
    .maxPathSegments(maxPathSegments)
    .maxSegmentLength(maxSegmentLength)
    .maxQueryParameters(maxQueryParameters)
    .maxQueryValueLength(maxQueryValueLength)
    .allowEncodedSlash(allowEncodedSlash)
    .allowDoubleEncoding(allowDoubleEncoding)
    .allowUnnormalized(allowUnnormalized)
    .build()
