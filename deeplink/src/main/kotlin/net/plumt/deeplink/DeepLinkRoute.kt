package net.plumt.deeplink

import net.plumt.deeplink.internal.TemplateMatcher

/** 선언하지 않은 쿼리 파라미터를 어떻게 다룰지. 값이 늘 수 있어 열거형이 아니다 — `when` 으로 가를 때는 `else` 를 둔다. */
public class UnknownQueryPolicy private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /** 조용히 버린다(기본). 결과에는 선언한 이름만 남는다. 추적용 파라미터가 붙는 링크에 맞다. */
        @JvmField
        public val DROP: UnknownQueryPolicy = UnknownQueryPolicy("DROP")

        /** 거부한다([Rejection.UnexpectedParameter]). 내부 전용 경로처럼 엄격해야 할 때. */
        @JvmField
        public val REJECT: UnknownQueryPolicy = UnknownQueryPolicy("REJECT")
    }
}

/**
 * 쿼리 파라미터 선언. [DeepLinkRoute.queryParams] 의 값이다.
 *
 * @property param 이름과 검증기
 * @property required 없으면 [Rejection.MissingParameter] 로 거부하는지
 */
public class QueryParamSpec internal constructor(
    public val param: Param<*>,
    public val required: Boolean,
) {
    override fun toString(): String = "QueryParamSpec(${param.name}, required=$required)"
}

/**
 * 허용 목록의 한 항목. 여기 등록하지 않은 스킴·호스트·경로는 모두 거부된다.
 *
 * ```kotlin
 * val itemId = Param.long("id", 1L..Long.MAX_VALUE)
 * val ref = Param.string("ref", maxLength = 32)
 * val item = DeepLinkRoute.builder("item", "myapp://item/{id}")
 *     .pathParam(itemId)
 *     .queryParam(ref)
 *     .build()
 * ```
 *
 * 템플릿에 있는데 [Builder.pathParam] 으로 선언하지 않은 경로 파라미터는
 * `Param.string(name)`(길이 [Param.DEFAULT_MAX_LENGTH], 이중 인코딩 거부)으로 검증한다.
 */
public class DeepLinkRoute private constructor(
    /** 경로 식별자. 한 처리기 안에서 유일. 로그·분석에서 URI 대신 쓴다. */
    public val id: String,
    /** 이 경로가 맞는 URI 를 판정한다. */
    public val matcher: RouteMatcher,
    /** 선언한 경로 파라미터(이름→선언). */
    public val pathParams: Map<String, Param<*>>,
    /** 선언한 쿼리 파라미터(이름→선언). */
    public val queryParams: Map<String, QueryParamSpec>,
    /** 이 경로를 열 수 있는 최소 신뢰 수준. */
    public val requiredTrust: TrustLevel,
    /** 선언하지 않은 쿼리 처리. */
    public val unknownQueryPolicy: UnknownQueryPolicy,
) {

    override fun toString(): String = "DeepLinkRoute(id=$id, pattern=${matcher.pattern}, requiredTrust=$requiredTrust)"

    /** [DeepLinkRoute] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor(private val id: String, private val matcher: RouteMatcher) {
        private val pathParams = LinkedHashMap<String, Param<*>>()
        private val queryParams = LinkedHashMap<String, QueryParamSpec>()
        private var requiredTrust = TrustLevel.PUBLIC
        private var unknownQueryPolicy = UnknownQueryPolicy.DROP

        /**
         * 경로 파라미터의 검증기를 정한다.
         *
         * @throws IllegalArgumentException 같은 이름을 두 번 선언했거나, 템플릿에 없는 이름일 때
         */
        public fun pathParam(param: Param<*>): Builder = apply {
            require(param.name !in pathParams) { "경로 파라미터 '${param.name}' 를 두 번 선언했다: route '$id'" }
            if (matcher is TemplateMatcher) {
                require(param.name in matcher.parameterNames) {
                    "템플릿 ${matcher.pattern} 에 없는 경로 파라미터다: '${param.name}'"
                }
            }
            pathParams[param.name] = param
        }

        /**
         * 받을 선택 쿼리 파라미터를 선언한다. 없으면 [ResolvedDeepLink.getOrNull] 이 `null` 이다.
         *
         * @throws IllegalArgumentException 같은 이름을 두 번 선언했을 때
         */
        public fun queryParam(param: Param<*>): Builder = queryParam(param, false)

        /**
         * 받을 쿼리 파라미터를 선언한다. 선언한 이름이 두 번 오면 거부된다.
         *
         * @param required `true` 면 없을 때 [Rejection.MissingParameter]
         * @throws IllegalArgumentException 같은 이름을 두 번 선언했을 때
         */
        public fun queryParam(param: Param<*>, required: Boolean): Builder = apply {
            require(param.name !in queryParams) { "쿼리 파라미터 '${param.name}' 를 두 번 선언했다: route '$id'" }
            queryParams[param.name] = QueryParamSpec(param, required)
        }

        /** 이 경로를 열 수 있는 최소 신뢰 수준. 기본 [TrustLevel.PUBLIC]. */
        public fun requiredTrust(level: TrustLevel): Builder = apply { requiredTrust = level }

        /** 선언하지 않은 쿼리 처리. 기본 [UnknownQueryPolicy.DROP]. */
        public fun unknownQueryParameters(policy: UnknownQueryPolicy): Builder = apply { unknownQueryPolicy = policy }

        /** @throws IllegalArgumentException 경로·쿼리에 같은 이름을 함께 선언했을 때 */
        public fun build(): DeepLinkRoute {
            val pathNames = pathParams.keys + ((matcher as? TemplateMatcher)?.parameterNames.orEmpty())
            val clash = pathNames.intersect(queryParams.keys)
            require(clash.isEmpty()) { "경로와 쿼리에 같은 이름을 선언했다: $clash (route '$id')" }
            return DeepLinkRoute(id, matcher, pathParams.toMap(), queryParams.toMap(), requiredTrust, unknownQueryPolicy)
        }
    }

    public companion object {
        private val ID = Regex("[A-Za-z0-9_.:-]{1,64}")

        /**
         * 템플릿([RouteMatcher.template]) 기반 경로.
         *
         * @throws IllegalArgumentException [id] 형식(영숫자와 `_.:-` 1~64자)이나 템플릿이 틀렸을 때
         */
        @JvmStatic
        public fun builder(id: String, template: String): Builder = builder(id, RouteMatcher.template(template))

        /**
         * 직접 구현한 [RouteMatcher] 기반 경로.
         *
         * @throws IllegalArgumentException [id] 형식이 틀렸거나 매처의 스킴·호스트가 소문자가 아닐 때
         */
        @JvmStatic
        public fun builder(id: String, matcher: RouteMatcher): Builder {
            require(ID.matches(id)) { "경로 id 는 영숫자와 _.:- 1~64자여야 한다: '$id'" }
            require(matcher.scheme.isNotEmpty() && matcher.scheme == matcher.scheme.lowercase()) {
                "매처의 scheme 은 비어 있지 않은 소문자여야 한다: '${matcher.scheme}'"
            }
            require(matcher.host.isNotEmpty() && matcher.host == matcher.host.lowercase()) {
                "매처의 host 는 비어 있지 않은 소문자여야 한다: '${matcher.host}'"
            }
            return Builder(id, matcher)
        }
    }
}

/**
 * Kotlin DSL: `deepLinkRoute("item", "myapp://item/{id}") { pathParam(id) }`
 *
 * @throws IllegalArgumentException [DeepLinkRoute.builder] 와 [DeepLinkRoute.Builder.build] 와 같다
 */
public fun deepLinkRoute(
    id: String,
    template: String,
    block: DeepLinkRoute.Builder.() -> Unit,
): DeepLinkRoute = DeepLinkRoute.builder(id, template).apply(block).build()

/**
 * 파라미터 선언 없는 템플릿 경로. `deepLinkRoute(id, template) {}` 와 같다.
 *
 * @throws IllegalArgumentException [DeepLinkRoute.builder] 와 같다
 */
public fun deepLinkRoute(id: String, template: String): DeepLinkRoute = DeepLinkRoute.builder(id, template).build()
