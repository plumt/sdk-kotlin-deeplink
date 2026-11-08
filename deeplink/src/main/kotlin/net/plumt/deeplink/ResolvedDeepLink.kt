package net.plumt.deeplink

/**
 * 검증을 모두 통과한 딥링크.
 *
 * 생성자가 공개되지 않아 [DeepLinks] 만 만들 수 있다. 그래서 인스턴스가 있다는 사실 자체가
 * "등록된 경로이고, 신뢰 수준을 충족했고, 모든 파라미터가 검증기를 통과했다" 는 증거다.
 * `copy` 가 없어 신뢰 수준을 바꿔 끼울 수도 없다.
 *
 * [toString] 은 경로 id·템플릿·신뢰 수준만 보이고 파라미터 값은 보이지 않는다.
 */
public class ResolvedDeepLink internal constructor(
    /** 맞은 경로. */
    public val route: DeepLinkRoute,
    /** 판정된 신뢰 수준. */
    public val trust: TrustLevel,
    /** 판정에 쓴 발신자 정보. */
    public val caller: CallerIdentity,
    /** 받은 URI(검증·디코딩 완료). [DeepLinkUri.toString] 은 값이 지워진 형태다. */
    public val uri: DeepLinkUri,
    private val values: Map<String, Any>,
    internal val owner: Any,
) {

    /**
     * 필수 파라미터(경로 파라미터 또는 required 쿼리)를 읽는다.
     *
     * @throws IllegalArgumentException [param] 이 이 경로에 선언되지 않았거나 값이 없을 때
     */
    public operator fun <T : Any> get(param: Param<T>): T =
        getOrNull(param) ?: throw IllegalArgumentException("'${param.name}' 값이 없다: route '${route.id}'")

    /**
     * 파라미터를 읽는다. 선택 쿼리가 오지 않았으면 `null`.
     *
     * @throws IllegalArgumentException [param] 이 이 경로에 선언한 그 선언 객체가 아닐 때(이름만 같은
     *   다른 선언으로 읽으면 타입이 어긋날 수 있다)
     */
    public fun <T : Any> getOrNull(param: Param<T>): T? {
        val declared = route.pathParams[param.name] ?: route.queryParams[param.name]?.param
        require(declared === param) { "'${param.name}' 는 route '${route.id}' 에 선언한 Param 이 아니다" }
        @Suppress("UNCHECKED_CAST")
        return values[param.name] as T?
    }

    /** 선언하지 않고 템플릿 기본값으로 검증된 경로 파라미터를 문자열로 읽는다. */
    public fun pathValue(name: String): String? = values[name] as? String

    override fun toString(): String = "ResolvedDeepLink(route=${route.id}, pattern=${route.matcher.pattern}, trust=$trust)"
}
