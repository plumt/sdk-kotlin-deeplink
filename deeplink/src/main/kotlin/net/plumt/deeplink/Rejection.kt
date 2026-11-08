package net.plumt.deeplink

/**
 * 딥링크를 받지 않은 이유.
 *
 * 어느 사유도 URI 원문·파라미터 값을 담지 않는다. 그래서 [toString] 을 그대로 로그·분석으로
 * 보내도 토큰이 새지 않는다. 사용자에게는 사유를 그대로 보이지 말고 "열 수 없는 링크" 수준으로만
 * 알린다 — 사유를 보이면 어떤 경로가 존재하는지 알려 주는 셈이다.
 *
 * [code] 는 분석 집계용 고정 문자열이다. 분기는 타입으로 한다. 닫힌 집합이라 `when` 에 `else` 가 필요 없다.
 */
public sealed interface Rejection {

    /** 집계용 고정 코드. 예: `malformed`, `unknown_host`. */
    public val code: String

    /** URI 형식이 틀렸거나 보안 규칙([UriRules])에 어긋났다. */
    public class Malformed(public val reason: MalformedReason) : Rejection {
        override val code: String get() = "malformed"
        override fun equals(other: Any?): Boolean = other is Malformed && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Malformed(reason=$reason)"
    }

    /** [UriRules] 의 상한을 넘었다. */
    public class TooLong(public val limit: LimitKind, public val max: Int, public val actual: Int) : Rejection {
        override val code: String get() = "too_long"
        override fun equals(other: Any?): Boolean = other is TooLong && limit == other.limit && max == other.max && actual == other.actual
        override fun hashCode(): Int = (limit.hashCode() * 31 + max) * 31 + actual
        override fun toString(): String = "TooLong(limit=$limit, max=$max, actual=$actual)"
    }

    /** 어떤 경로에도 등록되지 않은 스킴이다. 외부 열기([DeepLinks.externalIntent])에서는 https 가 아닌 스킴이다. */
    public class UnknownScheme(public val scheme: String) : Rejection {
        override val code: String get() = "unknown_scheme"
        override fun equals(other: Any?): Boolean = other is UnknownScheme && scheme == other.scheme
        override fun hashCode(): Int = scheme.hashCode()
        override fun toString(): String = "UnknownScheme(scheme=$scheme)"
    }

    /** 스킴은 알지만 등록되지 않은 호스트(또는 허용하지 않는 포트)다. 외부 열기에서는 허용 목록 밖 호스트다. */
    public class UnknownHost(public val scheme: String, public val host: String, public val port: Int?) : Rejection {
        override val code: String get() = "unknown_host"
        override fun equals(other: Any?): Boolean =
            other is UnknownHost && scheme == other.scheme && host == other.host && port == other.port
        override fun hashCode(): Int = (scheme.hashCode() * 31 + host.hashCode()) * 31 + (port ?: 0)
        override fun toString(): String = "UnknownHost(scheme=$scheme, host=$host, port=$port)"
    }

    /** 스킴·호스트는 맞지만 경로가 어느 경로 정의에도 맞지 않는다. */
    public class NoMatchingRoute(public val scheme: String, public val host: String) : Rejection {
        override val code: String get() = "no_matching_route"
        override fun equals(other: Any?): Boolean = other is NoMatchingRoute && scheme == other.scheme && host == other.host
        override fun hashCode(): Int = scheme.hashCode() * 31 + host.hashCode()
        override fun toString(): String = "NoMatchingRoute(scheme=$scheme, host=$host)"
    }

    /**
     * 발신자 신뢰 수준이 경로가 요구하는 수준보다 낮다.
     *
     * @property routeId 맞은 경로
     * @property required 경로가 요구한 수준
     * @property actual 신뢰 정책이 준 수준
     */
    public class InsufficientTrust(
        public val routeId: String,
        public val required: TrustLevel,
        public val actual: TrustLevel,
    ) : Rejection {
        override val code: String get() = "insufficient_trust"
        override fun equals(other: Any?): Boolean =
            other is InsufficientTrust && routeId == other.routeId && required == other.required && actual == other.actual
        override fun hashCode(): Int = (routeId.hashCode() * 31 + required.hashCode()) * 31 + actual.hashCode()
        override fun toString(): String = "InsufficientTrust(routeId=$routeId, required=$required, actual=$actual)"
    }

    /** 필수 파라미터가 없다. */
    public class MissingParameter(public val routeId: String, public val name: String) : Rejection {
        override val code: String get() = "missing_parameter"
        override fun equals(other: Any?): Boolean = other is MissingParameter && routeId == other.routeId && name == other.name
        override fun hashCode(): Int = routeId.hashCode() * 31 + name.hashCode()
        override fun toString(): String = "MissingParameter(routeId=$routeId, name=$name)"
    }

    /** 파라미터 값이 검증기를 통과하지 못했다. [reason] 은 검증기가 낸 설명이며 값은 담지 않는다. */
    public class InvalidParameter(public val routeId: String, public val name: String, public val reason: String) : Rejection {
        override val code: String get() = "invalid_parameter"
        override fun equals(other: Any?): Boolean =
            other is InvalidParameter && routeId == other.routeId && name == other.name && reason == other.reason
        override fun hashCode(): Int = (routeId.hashCode() * 31 + name.hashCode()) * 31 + reason.hashCode()
        override fun toString(): String = "InvalidParameter(routeId=$routeId, name=$name, reason=$reason)"
    }

    /** 선언한 쿼리 파라미터가 두 번 이상 왔다. 어느 값을 쓸지 모호하므로 거부한다. */
    public class DuplicateParameter(public val routeId: String, public val name: String) : Rejection {
        override val code: String get() = "duplicate_parameter"
        override fun equals(other: Any?): Boolean = other is DuplicateParameter && routeId == other.routeId && name == other.name
        override fun hashCode(): Int = routeId.hashCode() * 31 + name.hashCode()
        override fun toString(): String = "DuplicateParameter(routeId=$routeId, name=$name)"
    }

    /** 선언하지 않은 쿼리 파라미터가 왔고 경로가 [UnknownQueryPolicy.REJECT] 다. */
    public class UnexpectedParameter(public val routeId: String, public val name: String) : Rejection {
        override val code: String get() = "unexpected_parameter"
        override fun equals(other: Any?): Boolean = other is UnexpectedParameter && routeId == other.routeId && name == other.name
        override fun hashCode(): Int = routeId.hashCode() * 31 + name.hashCode()
        override fun toString(): String = "UnexpectedParameter(routeId=$routeId, name=$name)"
    }

    /** 인터셉터가 처리를 멈췄다. [reason] 은 인터셉터가 정한 로그용 문구다. */
    public class Intercepted(public val reason: String) : Rejection {
        override val code: String get() = "intercepted"
        override fun equals(other: Any?): Boolean = other is Intercepted && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Intercepted(reason=$reason)"
    }

    /**
     * 소비자가 정의한 사유. [DeepLinkRule] 이 돌려준다. 규칙이 예외를 던지면 `Custom("rule_exception", <예외 타입 이름>)` 이다.
     *
     * @property customCode 집계용 코드. [code] 는 `custom:<customCode>` 가 된다
     * @property detail 로그용 설명. 값을 담지 않는다
     */
    public class Custom(public val customCode: String, public val detail: String) : Rejection {
        override val code: String get() = "custom:$customCode"
        override fun equals(other: Any?): Boolean = other is Custom && customCode == other.customCode && detail == other.detail
        override fun hashCode(): Int = customCode.hashCode() * 31 + detail.hashCode()
        override fun toString(): String = "Custom(customCode=$customCode, detail=$detail)"
    }
}

/**
 * [Rejection.Malformed] 의 세부 사유. 보안 규칙이 늘면 값이 늘 수 있어 열거형이 아니다 — `when` 으로 가를 때는 `else` 를 둔다.
 */
public class MalformedReason private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<MalformedReason>()
        private fun of(name: String) = MalformedReason(name).also { all += it }

        /** 빈 문자열이거나 공백뿐이다. */
        @JvmField public val EMPTY: MalformedReason = of("EMPTY")

        /** 제어 문자·공백·양방향 서식 문자·`\`·`<>` 등 URI 에 올 수 없는 문자가 있다. */
        @JvmField public val ILLEGAL_CHARACTER: MalformedReason = of("ILLEGAL_CHARACTER")

        /** 스킴이 없거나 형식이 틀렸다. */
        @JvmField public val INVALID_SCHEME: MalformedReason = of("INVALID_SCHEME")

        /** `scheme://` 뒤 authority 가 없다. */
        @JvmField public val MISSING_AUTHORITY: MalformedReason = of("MISSING_AUTHORITY")

        /** `user@host` 형태의 사용자 정보가 있다. 호스트를 속이는 데 쓰이므로 받지 않는다. */
        @JvmField public val USERINFO_NOT_ALLOWED: MalformedReason = of("USERINFO_NOT_ALLOWED")

        /** 호스트가 비었거나 ASCII 도메인 형식이 아니다(IDN 은 punycode 로만 받는다). */
        @JvmField public val INVALID_HOST: MalformedReason = of("INVALID_HOST")

        /** 포트가 숫자가 아니거나 범위를 벗어났다. */
        @JvmField public val INVALID_PORT: MalformedReason = of("INVALID_PORT")

        /** `%` 뒤에 ASCII 16진 두 자리가 오지 않았거나 디코딩 결과가 올바른 UTF-8 이 아니다. */
        @JvmField public val INVALID_PERCENT_ENCODING: MalformedReason = of("INVALID_PERCENT_ENCODING")

        /** 경로 중간에 빈 조각(`//`)이 있다. */
        @JvmField public val EMPTY_SEGMENT: MalformedReason = of("EMPTY_SEGMENT")

        /** `.`·`..` 경로 조각(인코딩된 것 포함)이 있다. */
        @JvmField public val DOT_SEGMENT: MalformedReason = of("DOT_SEGMENT")

        /** 디코딩하면 `/`·`\` 가 되는 경로 조각이 있다. */
        @JvmField public val ENCODED_SLASH: MalformedReason = of("ENCODED_SLASH")

        /** 한 번 디코딩한 경로 조각·쿼리 이름에 `%` 가 남아 있다. */
        @JvmField public val DOUBLE_ENCODING: MalformedReason = of("DOUBLE_ENCODING")

        /** 경로 조각·쿼리 이름이 NFKC 정규형이 아니다(전각 `．`·`／`, 조합 문자 등). [UriRules.allowUnnormalized] 참고. */
        @JvmField public val NOT_NORMALIZED: MalformedReason = of("NOT_NORMALIZED")

        /** 쿼리 이름이 비었다. */
        @JvmField public val INVALID_QUERY: MalformedReason = of("INVALID_QUERY")

        /** 정의된 모든 값(문서 일치 테스트용). */
        internal val values: List<MalformedReason> get() = all.toList()
    }
}

/** [Rejection.TooLong] 이 가리키는 상한. 값이 늘 수 있어 열거형이 아니다. */
public class LimitKind private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<LimitKind>()
        private fun of(name: String) = LimitKind(name).also { all += it }

        /** [UriRules.maxUriLength] */
        @JvmField public val URI_LENGTH: LimitKind = of("URI_LENGTH")

        /** [UriRules.maxPathSegments] */
        @JvmField public val PATH_SEGMENTS: LimitKind = of("PATH_SEGMENTS")

        /** [UriRules.maxSegmentLength] */
        @JvmField public val SEGMENT_LENGTH: LimitKind = of("SEGMENT_LENGTH")

        /** [UriRules.maxQueryParameters] */
        @JvmField public val QUERY_PARAMETERS: LimitKind = of("QUERY_PARAMETERS")

        /** [UriRules.maxQueryValueLength] */
        @JvmField public val QUERY_VALUE_LENGTH: LimitKind = of("QUERY_VALUE_LENGTH")

        /** 정의된 모든 값(문서 일치 테스트용). */
        internal val values: List<LimitKind> get() = all.toList()
    }
}
