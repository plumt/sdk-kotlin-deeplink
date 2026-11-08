package net.plumt.deeplink

/** [DeepLinks.readLaunch]·[DeepLinks.readNewIntent] 결과. 닫힌 집합이라 `when` 에 `else` 가 필요 없다. */
public sealed interface IntentReadResult {

    /** 딥링크다. [DeepLinks.offer] 로 넣거나 [DeepLinks.handle] 로 처리한다. */
    public class Link(public val request: DeepLinkRequest) : IntentReadResult {
        override fun equals(other: Any?): Boolean = other is Link && request == other.request
        override fun hashCode(): Int = request.hashCode()
        override fun toString(): String = "Link(request=$request)"
    }

    /** 딥링크가 아니다(허용하지 않은 액션이거나 data 가 없거나 재생성·최근 앱에서 다시 연 실행). 평소 시작 흐름을 탄다. */
    public object NotDeepLink : IntentReadResult {
        override fun toString(): String = "NotDeepLink"
    }

    /** 적대적으로 보이는 인텐트다. 아무것도 열지 않고 평소 시작 흐름으로 보낸다. */
    public class Unsafe(public val reason: UnsafeIntentReason) : IntentReadResult {
        override fun equals(other: Any?): Boolean = other is Unsafe && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Unsafe(reason=$reason)"
    }
}

/** [IntentReadResult.Unsafe] 의 사유. 방어가 늘면 값이 늘 수 있어 열거형이 아니다 — `when` 으로 가를 때는 `else` 를 둔다. */
public class UnsafeIntentReason private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /** 셀렉터가 붙어 있다. 받은 인텐트에 셀렉터가 있을 이유가 없고, 되넘기면 엉뚱한 컴포넌트가 열린다. */
        @JvmField
        public val HAS_SELECTOR: UnsafeIntentReason = UnsafeIntentReason("HAS_SELECTOR")

        /**
         * extras 의 **구조**(키·길이·타입 표지)를 읽다 예외가 났다(손상된 Bundle).
         *
         * API 33+ 는 Parcelable·Serializable 값을 꺼낼 때까지 역직렬화하지 않으므로, 알 수 없는 클래스가 든
         * extras 는 여기서 감지되지 **않는다**. 리더는 그 값을 꺼내지 않으므로 그대로 두면 무해하다.
         * API 23~32 는 extras 에 처음 닿을 때 플랫폼이 Bundle 전체를 역직렬화하므로 알 수 없는 클래스도 여기서 걸린다.
         */
        @JvmField
        public val UNREADABLE_EXTRAS: UnsafeIntentReason = UnsafeIntentReason("UNREADABLE_EXTRAS")
    }
}

/** [DeepLinks.stringExtra] 등의 읽기 결과. 닫힌 집합이라 `when` 에 `else` 가 필요 없다. */
public sealed interface ExtraRead<out T : Any> {
    /** 값이 있고 형식이 맞다. [toString] 은 값을 보이지 않는다. */
    public class Present<out T : Any>(public val value: T) : ExtraRead<T> {
        override fun equals(other: Any?): Boolean = other is Present<*> && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "Present"
    }

    /** 키가 없다. */
    public object Absent : ExtraRead<Nothing> {
        override fun toString(): String = "Absent"
    }

    /** 키는 있는데 쓸 수 없다. */
    public class Invalid(public val reason: ExtraInvalidReason) : ExtraRead<Nothing> {
        override fun equals(other: Any?): Boolean = other is Invalid && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Invalid(reason=$reason)"
    }
}

/** [ExtraRead.Invalid] 의 사유. 값이 늘 수 있어 열거형이 아니다. */
public class ExtraInvalidReason private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /** 기대한 타입이 아니거나 값이 null 이다. */
        @JvmField
        public val WRONG_TYPE: ExtraInvalidReason = ExtraInvalidReason("WRONG_TYPE")

        /** 문자열이 상한보다 길다. */
        @JvmField
        public val TOO_LONG: ExtraInvalidReason = ExtraInvalidReason("TOO_LONG")

        /** extras 를 풀 수 없다. */
        @JvmField
        public val UNREADABLE: ExtraInvalidReason = ExtraInvalidReason("UNREADABLE")
    }
}
