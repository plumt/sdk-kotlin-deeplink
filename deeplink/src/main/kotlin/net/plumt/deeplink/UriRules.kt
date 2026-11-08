package net.plumt.deeplink

/**
 * URI 를 읽을 때 적용하는 길이 상한과 인코딩 규칙. 불변이고 [builder] 로 만든다.
 *
 * 기본값은 거부 쪽으로 기울어 있다. 상한을 넘거나 규칙에 어긋난 URI 는 [Rejection.TooLong]·
 * [Rejection.Malformed] 로 거부된다.
 *
 * @property maxUriLength URI 문자열 전체의 최대 길이(문자 수). 기본 2048
 * @property maxPathSegments 경로 조각의 최대 개수. 기본 16
 * @property maxSegmentLength 디코딩한 경로 조각 하나의 최대 길이. 기본 256
 * @property maxQueryParameters 쿼리 파라미터(이름=값 쌍)의 최대 개수. 선언하지 않은 이름도 센다. 기본 32
 * @property maxQueryValueLength 디코딩한 쿼리 값 하나의 최대 길이. 기본 1024
 * @property allowEncodedSlash `%2F`·`%5C` 처럼 디코딩하면 `/`·`\` 가 되는 경로 조각을 받을지.
 *   기본 거부 — 경로 파라미터 값이 다른 경로로 해석되는 것을 막는다
 * @property allowDoubleEncoding 한 번 디코딩한 경로 조각·쿼리 이름에 `%` 가 남아 있어도 받을지.
 *   기본 거부 — 뒤에서 한 번 더 디코딩하는 코드가 검증을 우회당하는 것을 막는다.
 *   쿼리 **값**은 파라미터 검증기가 판단한다([Param.string] 의 `allowPercentEscapes`)
 * @property allowUnnormalized NFKC 정규형이 아닌 경로 조각·쿼리 이름(전각 `．．`·`／`, NFD 조합 문자)을 받을지.
 *   기본 거부 — 값을 정규화하는 하류(파일 시스템·서버·IDNA)에서 `..`·`/` 로 바뀌는 것을 막는다.
 *   라이브러리는 값을 정규화하지 않는다. 쿼리 **값**은 검사하지 않으므로 파일 경로·URL 조립에 쓸 값은
 *   [Param.string] 의 `pattern` 으로 ASCII 를 강제한다
 */
public class UriRules private constructor(builder: Builder) {
    public val maxUriLength: Int = builder.maxUriLength
    public val maxPathSegments: Int = builder.maxPathSegments
    public val maxSegmentLength: Int = builder.maxSegmentLength
    public val maxQueryParameters: Int = builder.maxQueryParameters
    public val maxQueryValueLength: Int = builder.maxQueryValueLength
    public val allowEncodedSlash: Boolean = builder.allowEncodedSlash
    public val allowDoubleEncoding: Boolean = builder.allowDoubleEncoding
    public val allowUnnormalized: Boolean = builder.allowUnnormalized

    /** 이 규칙에서 시작하는 빌더. */
    public fun toBuilder(): Builder = Builder()
        .maxUriLength(maxUriLength)
        .maxPathSegments(maxPathSegments)
        .maxSegmentLength(maxSegmentLength)
        .maxQueryParameters(maxQueryParameters)
        .maxQueryValueLength(maxQueryValueLength)
        .allowEncodedSlash(allowEncodedSlash)
        .allowDoubleEncoding(allowDoubleEncoding)
        .allowUnnormalized(allowUnnormalized)

    override fun toString(): String =
        "UriRules(maxUriLength=$maxUriLength, maxPathSegments=$maxPathSegments, " +
            "maxSegmentLength=$maxSegmentLength, maxQueryParameters=$maxQueryParameters, " +
            "maxQueryValueLength=$maxQueryValueLength, allowEncodedSlash=$allowEncodedSlash, " +
            "allowDoubleEncoding=$allowDoubleEncoding, allowUnnormalized=$allowUnnormalized)"

    /** [UriRules] 를 만든다. 정하지 않은 값은 기본값이다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal var maxUriLength = 2048
        internal var maxPathSegments = 16
        internal var maxSegmentLength = 256
        internal var maxQueryParameters = 32
        internal var maxQueryValueLength = 1024
        internal var allowEncodedSlash = false
        internal var allowDoubleEncoding = false
        internal var allowUnnormalized = false

        /** @throws IllegalArgumentException [value] 가 1 미만일 때 */
        public fun maxUriLength(value: Int): Builder = apply { maxUriLength = positive("maxUriLength", value) }

        /** @throws IllegalArgumentException [value] 가 1 미만일 때 */
        public fun maxPathSegments(value: Int): Builder = apply { maxPathSegments = positive("maxPathSegments", value) }

        /** @throws IllegalArgumentException [value] 가 1 미만일 때 */
        public fun maxSegmentLength(value: Int): Builder = apply { maxSegmentLength = positive("maxSegmentLength", value) }

        /** @throws IllegalArgumentException [value] 가 1 미만일 때 */
        public fun maxQueryParameters(value: Int): Builder = apply { maxQueryParameters = positive("maxQueryParameters", value) }

        /** @throws IllegalArgumentException [value] 가 1 미만일 때 */
        public fun maxQueryValueLength(value: Int): Builder = apply { maxQueryValueLength = positive("maxQueryValueLength", value) }

        /** [UriRules.allowEncodedSlash] */
        public fun allowEncodedSlash(value: Boolean): Builder = apply { allowEncodedSlash = value }

        /** [UriRules.allowDoubleEncoding] */
        public fun allowDoubleEncoding(value: Boolean): Builder = apply { allowDoubleEncoding = value }

        /** [UriRules.allowUnnormalized] */
        public fun allowUnnormalized(value: Boolean): Builder = apply { allowUnnormalized = value }

        /** 불변 규칙을 만든다. */
        public fun build(): UriRules = UriRules(this)

        private fun positive(name: String, value: Int): Int {
            require(value >= 1) { "$name 은 1 이상이어야 한다: $value" }
            return value
        }
    }

    public companion object {
        /** 기본 규칙. */
        @JvmField
        public val DEFAULT: UriRules = Builder().build()

        /** 기본값에서 시작하는 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
