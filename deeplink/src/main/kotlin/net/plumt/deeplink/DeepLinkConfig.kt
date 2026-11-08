package net.plumt.deeplink

/**
 * [DeepLinks] 설정. 불변이고 [builder]·[deepLinkConfig] 로 만든다. 정하지 않은 값은 기본값이다.
 *
 * ```kotlin
 * val config = deepLinkConfig {
 *     route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
 *     interceptor(authInterceptor)
 * }
 * ```
 *
 * @property routes 허용 목록. 여기 등록하지 않은 스킴·호스트·경로는 모두 거부된다
 * @property interceptors 인터셉터. [DeepLinkInterceptor.priority] 순, 같으면 등록 순
 * @property listeners 결과 관찰자
 * @property rules 앱이 더한 검증 규칙. 등록 순
 * @property trustPolicy 신뢰 정책. `null` 이면 [DeepLinks.create] 가 자기 패키지로 [TrustPolicy.standard] 를 쓴다
 * @property uriRules URI 규칙. 기본 [UriRules.DEFAULT]
 * @property trustedCallers 서명까지 확인해 FIRST_PARTY 로 올릴 앱([CallerVerifier.create] 참고)
 * @property callerVerifier 직접 구현한 발신자 판정. `null` 이면 [trustedCallers] 로 기본 판정을 만든다
 * @property allowedActions 딥링크로 볼 인텐트 액션. 기본 `android.intent.action.VIEW` 하나
 * @property queueCapacity 앞 화면을 기다리는 링크의 최대 개수. 넘치면 가장 오래된 링크를 버린다. 기본 [DEFAULT_QUEUE_CAPACITY]
 * @property externalHosts [DeepLinks.externalIntent] 가 열어도 되는 https 호스트(정확히 일치). 기본 없음 — 모두 거부
 */
public class DeepLinkConfig private constructor(builder: Builder) {
    public val routes: List<DeepLinkRoute> = builder.routes.toList()
    public val interceptors: List<DeepLinkInterceptor> =
        builder.interceptors.withIndex().sortedWith(compareBy({ it.value.priority }, { it.index })).map { it.value }
    public val listeners: List<DeepLinkListener> = builder.listeners.toList()
    public val rules: List<DeepLinkRule> = builder.rules.toList()
    public val trustPolicy: TrustPolicy? = builder.trustPolicy
    public val uriRules: UriRules = builder.uriRules
    public val trustedCallers: List<TrustedCaller> = builder.trustedCallers.toList()
    public val callerVerifier: CallerVerifier? = builder.callerVerifier
    public val allowedActions: Set<String> = builder.allowedActions.toSet()
    public val queueCapacity: Int = builder.queueCapacity
    public val externalHosts: Set<String> = builder.externalHosts.toSet()

    override fun toString(): String =
        "DeepLinkConfig(routes=${routes.map { it.id }}, interceptors=${interceptors.size}, listeners=${listeners.size}, " +
            "rules=${rules.size}, trustedCallers=${trustedCallers.map { it.packageName }}, allowedActions=$allowedActions, " +
            "queueCapacity=$queueCapacity, externalHosts=${externalHosts.size})"

    /** [DeepLinkConfig] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal val routes = ArrayList<DeepLinkRoute>()
        internal val interceptors = ArrayList<DeepLinkInterceptor>()
        internal val listeners = ArrayList<DeepLinkListener>()
        internal val rules = ArrayList<DeepLinkRule>()
        internal var trustPolicy: TrustPolicy? = null
        internal var uriRules: UriRules = UriRules.DEFAULT
        internal val trustedCallers = ArrayList<TrustedCaller>()
        internal var callerVerifier: CallerVerifier? = null
        internal var allowedActions: Set<String> = setOf(ACTION_VIEW)
        internal var queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
        internal val externalHosts = LinkedHashSet<String>()

        /** 경로를 등록한다. */
        public fun route(route: DeepLinkRoute): Builder = apply { routes += route }

        /** 경로를 여러 개 등록한다. */
        public fun routes(routes: Iterable<DeepLinkRoute>): Builder = apply { this.routes += routes }

        /** 인터셉터를 등록한다. 실행 순서는 [DeepLinkInterceptor.priority], 같으면 등록 순서. */
        public fun interceptor(interceptor: DeepLinkInterceptor): Builder = apply { interceptors += interceptor }

        /** 결과 관찰자를 등록한다. */
        public fun listener(listener: DeepLinkListener): Builder = apply { listeners += listener }

        /** 검증 규칙을 더한다. */
        public fun rule(rule: DeepLinkRule): Builder = apply { rules += rule }

        /** 신뢰 정책을 바꾼다. 정하지 않으면 자기 패키지의 [TrustPolicy.standard]. */
        public fun trustPolicy(policy: TrustPolicy): Builder = apply { trustPolicy = policy }

        /** URI 규칙. 기본 [UriRules.DEFAULT]. */
        public fun uriRules(rules: UriRules): Builder = apply { uriRules = rules }

        /** 서명까지 확인해 FIRST_PARTY 로 올릴 앱을 더한다. */
        public fun trustedCaller(caller: TrustedCaller): Builder = apply { trustedCallers += caller }

        /** 서명까지 확인해 FIRST_PARTY 로 올릴 앱을 여럿 더한다. */
        public fun trustedCallers(callers: Iterable<TrustedCaller>): Builder = apply { trustedCallers += callers }

        /** 발신자 판정을 직접 구현한 것으로 바꾼다. [trustedCaller] 와 함께 쓸 수 없다. */
        public fun callerVerifier(verifier: CallerVerifier): Builder = apply { callerVerifier = verifier }

        /** 딥링크로 볼 인텐트 액션을 바꾼다. 기본 `android.intent.action.VIEW` 하나. */
        public fun allowedActions(actions: Set<String>): Builder = apply { allowedActions = actions.toSet() }

        /** 앞 화면을 기다리는 링크의 최대 개수. */
        public fun queueCapacity(capacity: Int): Builder = apply { queueCapacity = capacity }

        /** [DeepLinks.externalIntent] 가 열어도 되는 https 호스트를 더한다. 소문자 ASCII 호스트, 하위 도메인은 따로 적는다. */
        public fun externalHost(host: String): Builder = apply { externalHosts += host }

        /** [externalHost] 를 여럿 더한다. */
        public fun externalHosts(hosts: Iterable<String>): Builder = apply { externalHosts += hosts }

        /**
         * @throws IllegalArgumentException 경로가 없거나 경로 id 가 겹칠 때, [trustedCallers] 에 같은 패키지가 두 번 있을 때,
         *   [callerVerifier] 와 [trustedCaller] 를 함께 정했을 때, [allowedActions] 가 비었을 때, [queueCapacity] 가 1 미만일 때,
         *   [externalHost] 가 소문자 호스트가 아닐 때
         */
        public fun build(): DeepLinkConfig {
            require(routes.isNotEmpty()) { "경로를 하나 이상 등록해야 한다" }
            val duplicated = routes.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "경로 id 가 겹친다: $duplicated" }
            val callers = trustedCallers.groupBy { it.packageName }.filterValues { it.size > 1 }.keys
            require(callers.isEmpty()) { "trustedCallers 에 같은 패키지가 두 번 있다: $callers" }
            require(callerVerifier == null || trustedCallers.isEmpty()) {
                "callerVerifier 를 직접 정하면 trustedCallers 는 그 구현이 다룬다 — 함께 쓸 수 없다"
            }
            require(allowedActions.isNotEmpty()) { "allowedActions 가 비었다" }
            require(queueCapacity >= 1) { "queueCapacity 는 1 이상이어야 한다: $queueCapacity" }
            externalHosts.forEach { host ->
                val probe = DeepLinkUri.parseOrNull("https://$host")
                require(probe != null && probe.host == host && probe.port == null && probe.pathSegments.isEmpty()) {
                    "externalHost 는 소문자 호스트여야 한다: '$host'"
                }
            }
            return DeepLinkConfig(this)
        }
    }

    public companion object {
        /** [queueCapacity] 기본값. 쌓인 링크는 복귀할 때 차례로 처리되므로 작게 둔다. */
        public const val DEFAULT_QUEUE_CAPACITY: Int = 8

        // Intent.ACTION_VIEW 와 같은 값. 설정은 Android 클래스 없이 만들 수 있어야 한다(가짜·JVM 테스트)
        private const val ACTION_VIEW = "android.intent.action.VIEW"

        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * Kotlin DSL: `deepLinkConfig { route(...); interceptor(...) }`
 *
 * @throws IllegalArgumentException [DeepLinkConfig.Builder.build] 와 같다
 */
public fun deepLinkConfig(block: DeepLinkConfig.Builder.() -> Unit): DeepLinkConfig =
    DeepLinkConfig.builder().apply(block).build()
