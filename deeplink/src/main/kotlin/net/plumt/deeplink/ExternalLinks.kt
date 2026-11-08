package net.plumt.deeplink

import android.content.Intent

/**
 * [DeepLinks.externalIntent] 결과. 닫힌 집합이라 `when` 에 `else` 가 필요 없다.
 */
public sealed interface ExternalLinkResult {

    /**
     * 열어도 되는 인텐트. `ACTION_VIEW` + `CATEGORY_BROWSABLE`, 컴포넌트·패키지·셀렉터·extras 없음, data 는 검증한 정규형 URL 이다.
     * [toString] 은 URI 를 보이지 않는다.
     */
    public class Ready(public val intent: Intent) : ExternalLinkResult {
        override fun toString(): String = "Ready"
    }

    /** 열지 않는다. 사유는 [Rejection.Malformed]·[Rejection.TooLong](형식), [Rejection.UnknownScheme](https 아님), [Rejection.UnknownHost](허용 목록 밖·포트). */
    public class Rejected(public val rejection: Rejection) : ExternalLinkResult {
        override fun equals(other: Any?): Boolean = other is Rejected && rejection == other.rejection
        override fun hashCode(): Int = rejection.hashCode()
        override fun toString(): String = "Rejected(rejection=$rejection)"
    }
}

/** [DeepLinks.openExternal] 결과. 닫힌 집합이라 `when` 에 `else` 가 필요 없다. */
public sealed interface ExternalOpenResult {

    /** 시스템에 넘겼다. 어느 앱(브라우저 등)이 열었는지는 알 수 없다. */
    public object Opened : ExternalOpenResult {
        override fun toString(): String = "Opened"
    }

    /** 검증을 통과하지 못해 열지 않았다. 사유는 [ExternalLinkResult.Rejected] 와 같다. */
    public class Rejected(public val rejection: Rejection) : ExternalOpenResult {
        override fun equals(other: Any?): Boolean = other is Rejected && rejection == other.rejection
        override fun hashCode(): Int = rejection.hashCode()
        override fun toString(): String = "Rejected(rejection=$rejection)"
    }

    /** 열 앱이 없다(`ActivityNotFoundException`). */
    public object NoActivity : ExternalOpenResult {
        override fun toString(): String = "NoActivity"
    }
}

/** 콜백 형태 [DeepLinks.handle]·[DeepLinks.resume] 의 취소 핸들. */
public interface DeepLinkHandle {
    /** 처리를 취소한다. 결과 콜백은 불리지 않는다. 이미 끝났으면 아무 일도 없다. 여러 번 불러도 된다. */
    public fun cancel()
}
