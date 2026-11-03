package net.plumt.deeplink

/**
 * URI 를 읽을 때 적용하는 길이 상한과 인코딩 규칙.
 *
 * 기본값은 거부 쪽으로 기울어 있다. 상한을 넘거나 규칙에 어긋난 URI 는 [Rejection.TooLong]·
 * [Rejection.Malformed] 로 거부된다. 모든 상한은 1 이상이어야 하며, 어기면 생성 시
 * [IllegalArgumentException] 을 던진다.
 *
 * @property maxUriLength URI 문자열 전체의 최대 길이(문자 수)
 * @property maxPathSegments 경로 조각의 최대 개수
 * @property maxSegmentLength 디코딩한 경로 조각 하나의 최대 길이
 * @property maxQueryParameters 쿼리 파라미터(이름=값 쌍)의 최대 개수. 선언하지 않은 이름도 센다
 * @property maxQueryValueLength 디코딩한 쿼리 값 하나의 최대 길이
 * @property allowEncodedSlash `%2F`·`%5C` 처럼 디코딩하면 `/`·`\` 가 되는 경로 조각을 받을지.
 *   기본 거부 — 경로 파라미터 값이 다른 경로로 해석되는 것을 막는다
 * @property allowDoubleEncoding 한 번 디코딩한 경로 조각·쿼리 이름에 `%XX` 가 다시 남아 있어도 받을지.
 *   기본 거부 — 뒤에서 한 번 더 디코딩하는 코드가 검증을 우회당하는 것을 막는다.
 *   쿼리 **값**은 파라미터 검증기가 판단한다([Param.string] 의 `allowPercentEscapes`)
 */
public class UriRules(
    public val maxUriLength: Int = 2048,
    public val maxPathSegments: Int = 16,
    public val maxSegmentLength: Int = 256,
    public val maxQueryParameters: Int = 32,
    public val maxQueryValueLength: Int = 1024,
    public val allowEncodedSlash: Boolean = false,
    public val allowDoubleEncoding: Boolean = false,
) {
    init {
        requirePositive("maxUriLength", maxUriLength)
        requirePositive("maxPathSegments", maxPathSegments)
        requirePositive("maxSegmentLength", maxSegmentLength)
        requirePositive("maxQueryParameters", maxQueryParameters)
        requirePositive("maxQueryValueLength", maxQueryValueLength)
    }

    override fun toString(): String =
        "UriRules(maxUriLength=$maxUriLength, maxPathSegments=$maxPathSegments, " +
            "maxSegmentLength=$maxSegmentLength, maxQueryParameters=$maxQueryParameters, " +
            "maxQueryValueLength=$maxQueryValueLength, allowEncodedSlash=$allowEncodedSlash, " +
            "allowDoubleEncoding=$allowDoubleEncoding)"

    public companion object {
        /** 기본 규칙. */
        @JvmField
        public val DEFAULT: UriRules = UriRules()

        private fun requirePositive(name: String, value: Int) {
            require(value >= 1) { "$name 은 1 이상이어야 한다: $value" }
        }
    }
}
