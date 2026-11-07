package net.plumt.deeplink

/**
 * 딥링크를 받지 않은 이유.
 *
 * 어느 사유도 URI 원문·파라미터 값을 담지 않는다. 그래서 [toString] 을 그대로 로그·분석으로
 * 보내도 토큰이 새지 않는다. 사용자에게는 사유를 그대로 보이지 말고 "열 수 없는 링크" 수준으로만
 * 알린다 — 사유를 보이면 어떤 경로가 존재하는지 알려 주는 셈이다.
 *
 * [code] 는 분석 집계용 고정 문자열이다. 분기는 타입으로 한다.
 */
public sealed class Rejection {

    /** 집계용 고정 코드. 예: `malformed`, `unknown_host`. */
    public abstract val code: String

    /** URI 형식이 틀렸거나 보안 규칙([UriRules])에 어긋났다. */
    public data class Malformed(val reason: MalformedReason) : Rejection() {
        override val code: String get() = "malformed"
    }

    /** [UriRules] 의 상한을 넘었다. */
    public data class TooLong(val limit: LimitKind, val max: Int, val actual: Int) : Rejection() {
        override val code: String get() = "too_long"
    }

    /** 어떤 경로에도 등록되지 않은 스킴이다. */
    public data class UnknownScheme(val scheme: String) : Rejection() {
        override val code: String get() = "unknown_scheme"
    }

    /** 스킴은 알지만 등록되지 않은 호스트(또는 허용하지 않는 포트)다. */
    public data class UnknownHost(val scheme: String, val host: String, val port: Int?) : Rejection() {
        override val code: String get() = "unknown_host"
    }

    /** 스킴·호스트는 맞지만 경로가 어느 경로 정의에도 맞지 않는다. */
    public data class NoMatchingRoute(val scheme: String, val host: String) : Rejection() {
        override val code: String get() = "no_matching_route"
    }

    /** 발신자 신뢰 수준이 경로가 요구하는 수준보다 낮다. */
    public data class InsufficientTrust(
        val routeId: String,
        val required: TrustLevel,
        val actual: TrustLevel,
    ) : Rejection() {
        override val code: String get() = "insufficient_trust"
    }

    /** 필수 파라미터가 없다. */
    public data class MissingParameter(val routeId: String, val name: String) : Rejection() {
        override val code: String get() = "missing_parameter"
    }

    /** 파라미터 값이 검증기를 통과하지 못했다. [reason] 은 검증기가 낸 설명이며 값은 담지 않는다. */
    public data class InvalidParameter(val routeId: String, val name: String, val reason: String) : Rejection() {
        override val code: String get() = "invalid_parameter"
    }

    /** 선언한 쿼리 파라미터가 두 번 이상 왔다. 어느 값을 쓸지 모호하므로 거부한다. */
    public data class DuplicateParameter(val routeId: String, val name: String) : Rejection() {
        override val code: String get() = "duplicate_parameter"
    }

    /** 선언하지 않은 쿼리 파라미터가 왔고 경로가 [UnknownQueryPolicy.REJECT] 다. */
    public data class UnexpectedParameter(val routeId: String, val name: String) : Rejection() {
        override val code: String get() = "unexpected_parameter"
    }

    /** 인터셉터가 처리를 멈췄다. [reason] 은 인터셉터가 정한 로그용 문구다. */
    public data class Intercepted(val reason: String) : Rejection() {
        override val code: String get() = "intercepted"
    }

    /**
     * 소비자가 정의한 사유. 플랫폼 어댑터·사용자 정의 단계가 쓴다.
     *
     * @property customCode 집계용 코드. [code] 는 `custom:<customCode>` 가 된다
     */
    public data class Custom(val customCode: String, val detail: String) : Rejection() {
        override val code: String get() = "custom:$customCode"
    }
}

/** [Rejection.Malformed] 의 세부 사유. */
public enum class MalformedReason {
    /** 빈 문자열이거나 공백뿐이다. */
    EMPTY,

    /** 제어 문자·공백·양방향 서식 문자·`\`·`<>` 등 URI 에 올 수 없는 문자가 있다. */
    ILLEGAL_CHARACTER,

    /** 스킴이 없거나 형식이 틀렸다. */
    INVALID_SCHEME,

    /** `scheme://` 뒤 authority 가 없다. */
    MISSING_AUTHORITY,

    /** `user@host` 형태의 사용자 정보가 있다. 호스트를 속이는 데 쓰이므로 받지 않는다. */
    USERINFO_NOT_ALLOWED,

    /** 호스트가 비었거나 ASCII 도메인 형식이 아니다(IDN 은 punycode 로만 받는다). */
    INVALID_HOST,

    /** 포트가 숫자가 아니거나 범위를 벗어났다. */
    INVALID_PORT,

    /** `%` 뒤에 ASCII 16진 두 자리가 오지 않았거나 디코딩 결과가 올바른 UTF-8 이 아니다. */
    INVALID_PERCENT_ENCODING,

    /** 경로 중간에 빈 조각(`//`)이 있다. */
    EMPTY_SEGMENT,

    /** `.`·`..` 경로 조각(인코딩된 것 포함)이 있다. */
    DOT_SEGMENT,

    /** 디코딩하면 `/`·`\` 가 되는 경로 조각이 있다. */
    ENCODED_SLASH,

    /** 한 번 디코딩한 경로 조각·쿼리 이름에 `%` 가 남아 있다. */
    DOUBLE_ENCODING,

    /** 경로 조각·쿼리 이름이 NFKC 정규형이 아니다(전각 `．`·`／`, 조합 문자 등). [UriRules.allowUnnormalized] 참고. */
    NOT_NORMALIZED,

    /** 쿼리 이름이 비었다. */
    INVALID_QUERY,
}

/** [Rejection.TooLong] 이 가리키는 상한. */
public enum class LimitKind {
    URI_LENGTH,
    PATH_SEGMENTS,
    SEGMENT_LENGTH,
    QUERY_PARAMETERS,
    QUERY_VALUE_LENGTH,
}
