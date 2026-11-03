package net.plumt.deeplink

import net.plumt.deeplink.internal.UriParser

/**
 * 검증을 거쳐 분해한 URI. `android.net.Uri`·`java.net.URI` 대신 이 타입을 쓴다.
 *
 * [parse] 만 만들 수 있고, 만들어졌다면 [UriRules] 를 모두 통과한 것이다.
 *
 * - [scheme]·[host] 는 소문자다. `http` 80·`https` 443 같은 기본 포트는 [port] 에서 지운다
 * - [pathSegments]·[queryParameters] 는 **정확히 한 번** 퍼센트 디코딩한 값이다.
 *   경로 조각은 `/` 로 먼저 나눈 뒤 디코딩하므로 `%2F` 가 새 조각을 만들지 않는다
 * - 쿼리에서 `+` 는 공백으로 읽는다(HTML 폼 관례). 경로에서는 `+` 그대로다
 * - 프래그먼트(`#...`)는 형식만 검사하고 버린다
 *
 * [toString] 은 쿼리 **값**을 지운 [redacted] 를 돌려준다. 원문이 필요하면 원래 문자열을 쓴다.
 */
public class DeepLinkUri internal constructor(
    /** 소문자 스킴. */
    public val scheme: String,
    /** 소문자 호스트. */
    public val host: String,
    /** 명시한 포트. 없거나 스킴의 기본 포트면 `null`. */
    public val port: Int?,
    /** 디코딩한 경로 조각. 끝의 `/` 는 무시한다. */
    public val pathSegments: List<String>,
    /** 디코딩한 쿼리. 이름마다 나온 순서대로 값 목록을 둔다. */
    public val queryParameters: Map<String, List<String>>,
) {

    /** 쿼리 이름 [name] 의 첫 값. 없으면 `null`. */
    public fun queryParameter(name: String): String? = queryParameters[name]?.firstOrNull()

    /**
     * 로그용 문자열. 스킴·호스트·포트·경로 조각 개수와 쿼리 이름만 남기고, 경로 조각과 쿼리 값은
     * 모두 별표 한 글자로 바꾼다. 경로 조각에도 토큰이 들어갈 수 있어서다.
     */
    public fun redacted(): String = buildString {
        append(scheme).append("://").append(host)
        if (port != null) append(':').append(port)
        pathSegments.forEach { _ -> append("/*") }
        if (queryParameters.isNotEmpty()) {
            append('?')
            append(queryParameters.keys.joinToString("&") { "${UriParser.encodeForLog(it)}=*" })
        }
    }

    override fun toString(): String = redacted()

    override fun equals(other: Any?): Boolean =
        other is DeepLinkUri && scheme == other.scheme && host == other.host && port == other.port &&
            pathSegments == other.pathSegments && queryParameters == other.queryParameters

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + (port ?: 0)
        result = 31 * result + pathSegments.hashCode()
        result = 31 * result + queryParameters.hashCode()
        return result
    }

    public companion object {
        /**
         * [input] 을 [rules] 로 검증하며 분해한다.
         *
         * @throws IllegalArgumentException 형식이 틀렸거나 규칙에 어긋날 때. 메시지에는 사유만 있고
         *   입력 원문은 들어가지 않는다(토큰이 섞여 있을 수 있다)
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(input: String, rules: UriRules = UriRules.DEFAULT): DeepLinkUri =
            when (val result = UriParser.parse(input, rules)) {
                is UriParser.Result.Ok -> result.uri
                is UriParser.Result.Fail -> throw IllegalArgumentException("URI 를 받을 수 없다: ${result.rejection}")
            }

        /** [parse] 와 같되 실패하면 `null` 을 돌려준다. 사유가 필요하면 [parse] 나 [DeepLinkHandler.resolve] 를 쓴다. */
        @JvmStatic
        @JvmOverloads
        public fun parseOrNull(input: String, rules: UriRules = UriRules.DEFAULT): DeepLinkUri? =
            (UriParser.parse(input, rules) as? UriParser.Result.Ok)?.uri
    }
}
