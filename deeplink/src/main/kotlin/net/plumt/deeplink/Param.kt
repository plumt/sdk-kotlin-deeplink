package net.plumt.deeplink

import java.util.UUID

/**
 * 파라미터 값 하나의 검증 결과.
 *
 * [Invalid.reason] 은 로그·분석에 그대로 실리므로 **값을 담지 않는다**.
 */
public sealed interface ParamCheck<out T : Any> {
    /** 통과. [value] 는 변환된 값. */
    public class Valid<out T : Any>(public val value: T) : ParamCheck<T> {
        override fun equals(other: Any?): Boolean = other is Valid<*> && value == other.value
        override fun hashCode(): Int = value.hashCode()

        /** 값을 담지 않는다. */
        override fun toString(): String = "Valid"
    }

    /** 실패. [reason] 은 값을 담지 않는다. */
    public class Invalid(public val reason: String) : ParamCheck<Nothing> {
        override fun equals(other: Any?): Boolean = other is Invalid && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Invalid(reason=$reason)"
    }
}

/** 디코딩한 문자열 값을 검증하고 [T] 로 바꾼다. 교체·추가해 자기 형식을 넣는다. */
public fun interface ParamValidator<out T : Any> {
    /** [raw] 를 검증한다. 예외를 던지지 말고 [ParamCheck.Invalid] 를 돌려준다. */
    public fun validate(raw: String): ParamCheck<T>
}

/**
 * 이름과 검증기를 묶은 파라미터 선언. 경로 정의에 등록하고, 결과에서 [ResolvedDeepLink.get] 의
 * 키로 쓴다.
 *
 * @property name 템플릿의 `{name}` 또는 쿼리 이름. 영숫자와 `_-.` 1~64자
 * @throws IllegalArgumentException 이름 형식이 틀렸을 때
 */
public class Param<out T : Any>(
    public val name: String,
    public val validator: ParamValidator<T>,
) {
    init {
        require(NAME.matches(name)) { "파라미터 이름은 영숫자와 _-. 1~64자여야 한다: '$name'" }
    }

    override fun toString(): String = "Param($name)"

    public companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")
        private val INT_TEXT = Regex("0|-?[1-9][0-9]*")
        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** 템플릿에 있는데 선언하지 않은 경로 파라미터에 쓰는 기본 길이 상한. */
        public const val DEFAULT_MAX_LENGTH: Int = 256

        /** 문자열. 길이 상한 [DEFAULT_MAX_LENGTH], 형식 검사 없음, 이중 인코딩 거부. */
        @JvmStatic
        public fun string(name: String): Param<String> = string(name, DEFAULT_MAX_LENGTH, null, false)

        /**
         * 문자열. 형식 검사 없음, 이중 인코딩 거부.
         *
         * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
         */
        @JvmStatic
        public fun string(name: String, maxLength: Int): Param<String> = string(name, maxLength, null, false)

        /**
         * 문자열. 이중 인코딩 거부.
         *
         * @param pattern 값 전체가 맞아야 하는 정규식. `null` 이면 검사하지 않는다
         * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
         */
        @JvmStatic
        public fun string(name: String, maxLength: Int, pattern: Regex?): Param<String> = string(name, maxLength, pattern, false)

        /**
         * 문자열.
         *
         * @param maxLength 최대 길이(문자 수). 1 이상
         * @param pattern 값 전체가 맞아야 하는 정규식. `null` 이면 검사하지 않는다
         * @param allowPercentEscapes 디코딩한 값에 `%` 가 남아도 받을지. 기본 거부(이중 인코딩).
         *   `%` 뒤가 16진수 꼴이 아니어도 거부한다 — JDK `URLDecoder` 처럼 비 ASCII 숫자를 16진으로 읽는 하류가 있다
         * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
         */
        @JvmStatic
        public fun string(
            name: String,
            maxLength: Int,
            pattern: Regex?,
            allowPercentEscapes: Boolean,
        ): Param<String> {
            require(maxLength >= 1) { "maxLength 는 1 이상이어야 한다: $maxLength" }
            return Param(name) { raw ->
                when {
                    raw.isEmpty() -> ParamCheck.Invalid("비어 있다")
                    raw.length > maxLength -> ParamCheck.Invalid("길이 ${raw.length} 가 상한 $maxLength 을 넘는다")
                    !allowPercentEscapes && raw.contains('%') -> ParamCheck.Invalid("이중 인코딩된 값이다")
                    pattern != null && !pattern.matches(raw) -> ParamCheck.Invalid("형식이 맞지 않는다")
                    else -> ParamCheck.Valid(raw)
                }
            }
        }

        /** 10진 정수(Int 전 범위). 표기 규칙은 범위를 받는 오버로드와 같다. */
        @JvmStatic
        public fun int(name: String): Param<Int> = int(name, Int.MIN_VALUE..Int.MAX_VALUE)

        /**
         * 10진 정수. 앞자리 0·`+` 부호·`-0` 은 받지 않는다(같은 값의 다른 표기를 막는다).
         *
         * @throws IllegalArgumentException [range] 가 비었을 때
         */
        @JvmStatic
        public fun int(name: String, range: IntRange): Param<Int> {
            require(!range.isEmpty()) { "range 가 비었다: $range" }
            return Param(name) { raw ->
                val value = if (INT_TEXT.matches(raw)) raw.toIntOrNull() else null
                when {
                    value == null -> ParamCheck.Invalid("정수가 아니다")
                    value !in range -> ParamCheck.Invalid("범위 $range 밖이다")
                    else -> ParamCheck.Valid(value)
                }
            }
        }

        /** 10진 정수(Long 전 범위). 표기 규칙은 [int] 와 같다. */
        @JvmStatic
        public fun long(name: String): Param<Long> = long(name, Long.MIN_VALUE..Long.MAX_VALUE)

        /**
         * 10진 정수(Long). 표기 규칙은 [int] 와 같다.
         *
         * @throws IllegalArgumentException [range] 가 비었을 때
         */
        @JvmStatic
        public fun long(name: String, range: LongRange): Param<Long> {
            require(!range.isEmpty()) { "range 가 비었다: $range" }
            return Param(name) { raw ->
                val value = if (INT_TEXT.matches(raw)) raw.toLongOrNull() else null
                when {
                    value == null -> ParamCheck.Invalid("정수가 아니다")
                    value !in range -> ParamCheck.Invalid("범위 $range 밖이다")
                    else -> ParamCheck.Valid(value)
                }
            }
        }

        /** `true`·`false`(소문자)만 받는다. */
        @JvmStatic
        public fun boolean(name: String): Param<Boolean> = Param(name) { raw ->
            when (raw) {
                "true" -> ParamCheck.Valid(true)
                "false" -> ParamCheck.Valid(false)
                else -> ParamCheck.Invalid("true·false 가 아니다")
            }
        }

        /**
         * 정해진 값 중 하나. 대소문자를 구분한다.
         *
         * @throws IllegalArgumentException [allowed] 가 비었을 때
         */
        @JvmStatic
        public fun oneOf(name: String, allowed: Set<String>): Param<String> {
            require(allowed.isNotEmpty()) { "allowed 가 비었다" }
            val copy = allowed.toSet()
            return Param(name) { raw ->
                if (raw in copy) ParamCheck.Valid(raw) else ParamCheck.Invalid("허용 값이 아니다")
            }
        }

        /** 하이픈이 있는 표준 표기의 UUID. */
        @JvmStatic
        public fun uuid(name: String): Param<UUID> = Param(name) { raw ->
            if (UUID_TEXT.matches(raw)) ParamCheck.Valid(UUID.fromString(raw)) else ParamCheck.Invalid("UUID 가 아니다")
        }

        /**
         * 다른 곳으로 보낼 https URL. [UriRules.DEFAULT] 로 다시 검증한다. 아래 오버로드를 본다.
         *
         * @throws IllegalArgumentException [allowedHosts] 가 비었거나 소문자 호스트가 아닐 때
         */
        @JvmStatic
        public fun httpsUrl(name: String, allowedHosts: Set<String>): Param<DeepLinkUri> = httpsUrl(name, allowedHosts, UriRules.DEFAULT)

        /**
         * 다른 곳으로 보낼 https URL. **오픈 리다이렉트 방어**용이다.
         *
         * 값을 [DeepLinkUri] 로 다시 검증하고, 스킴이 `https` 이며 포트가 없고 호스트가 [allowedHosts]
         * 와 정확히 같을 때만 받는다. 하위 도메인은 따로 적어야 한다.
         *
         * @param allowedHosts 소문자 ASCII 호스트 목록. 비어 있으면 안 된다
         * @throws IllegalArgumentException [allowedHosts] 가 비었거나 소문자 호스트가 아닐 때
         */
        @JvmStatic
        public fun httpsUrl(name: String, allowedHosts: Set<String>, rules: UriRules): Param<DeepLinkUri> {
            require(allowedHosts.isNotEmpty()) { "allowedHosts 가 비었다" }
            allowedHosts.forEach { host ->
                val probe = DeepLinkUri.parseOrNull("https://$host", rules)
                require(probe != null && probe.host == host && probe.port == null) { "allowedHosts 의 항목이 소문자 호스트가 아니다: '$host'" }
            }
            val hosts = allowedHosts.toSet()
            return Param(name) { raw ->
                val uri = DeepLinkUri.parseOrNull(raw, rules)
                when {
                    uri == null -> ParamCheck.Invalid("URL 형식이 아니다")
                    uri.scheme != "https" -> ParamCheck.Invalid("https 가 아니다")
                    uri.port != null -> ParamCheck.Invalid("포트를 지정할 수 없다")
                    uri.host !in hosts -> ParamCheck.Invalid("허용하지 않은 호스트다")
                    else -> ParamCheck.Valid(uri)
                }
            }
        }
    }
}
