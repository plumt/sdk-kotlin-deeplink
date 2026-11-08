package net.plumt.deeplink

/**
 * 처리할 딥링크 하나. 보통 [DeepLinks.readLaunch]·[DeepLinks.readNewIntent] 가 만든다.
 *
 * [toString] 은 URI 를 보이지 않는다(토큰이 섞여 있을 수 있다).
 *
 * @property uri 받은 URI 문자열 그대로
 * @property caller 발신자. 모르면 [CallerIdentity.UNKNOWN]
 */
public class DeepLinkRequest(
    public val uri: String,
    public val caller: CallerIdentity,
) {
    /** 발신자를 모르는 요청([CallerIdentity.UNKNOWN]). */
    public constructor(uri: String) : this(uri, CallerIdentity.UNKNOWN)

    override fun toString(): String = "DeepLinkRequest(uriLength=${uri.length}, caller=$caller)"
}

/** [DeepLinks.resolve] 결과. 닫힌 집합이라 `when` 에 `else` 가 필요 없다. */
public sealed interface DeepLinkResolution {
    /** 통과. */
    public class Resolved(public val link: ResolvedDeepLink) : DeepLinkResolution {
        override fun equals(other: Any?): Boolean = other is Resolved && link == other.link
        override fun hashCode(): Int = link.hashCode()
        override fun toString(): String = "Resolved(link=$link)"
    }

    /** 거부. */
    public class Rejected(public val rejection: Rejection) : DeepLinkResolution {
        override fun equals(other: Any?): Boolean = other is Rejected && rejection == other.rejection
        override fun hashCode(): Int = rejection.hashCode()
        override fun toString(): String = "Rejected(rejection=$rejection)"
    }
}

/**
 * [DeepLinks.handle] 의 최종 결과. 실패도 예외가 아니라 이 값으로 온다
 * (취소 `CancellationException` 과 `Error` 만 그대로 전파된다). 닫힌 집합이라 `when` 에 `else` 가 필요 없다.
 *
 * [toString] 은 URI 원문·파라미터 값을 담지 않는다.
 */
public sealed interface DeepLinkOutcome {

    /** 라우터가 이동을 처리했다. */
    public class Handled(public val link: ResolvedDeepLink) : DeepLinkOutcome {
        override fun equals(other: Any?): Boolean = other is Handled && link == other.link
        override fun hashCode(): Int = link.hashCode()
        override fun toString(): String = "Handled(link=$link)"
    }

    /** 검증은 통과했지만 라우터가 `false` 를 돌려줬다. 호출부가 기본 화면으로 보낸다. */
    public class Unhandled(public val link: ResolvedDeepLink) : DeepLinkOutcome {
        override fun equals(other: Any?): Boolean = other is Unhandled && link == other.link
        override fun hashCode(): Int = link.hashCode()
        override fun toString(): String = "Unhandled(link=$link)"
    }

    /** 검증이나 인터셉터에서 거부됐다. */
    public class Rejected(public val rejection: Rejection) : DeepLinkOutcome {
        override fun equals(other: Any?): Boolean = other is Rejected && rejection == other.rejection
        override fun hashCode(): Int = rejection.hashCode()
        override fun toString(): String = "Rejected(rejection=$rejection)"
    }

    /**
     * 인터셉터가 처리를 미뤘다(예: 로그인 필요). 조건이 갖춰지면 [DeepLinks.resume] 에 [link] 를
     * 넘겨 다시 진행한다. [link] 는 메모리에만 두고 디스크에 쓰지 않는다.
     */
    public class Deferred(public val link: ResolvedDeepLink, public val reason: String) : DeepLinkOutcome {
        override fun equals(other: Any?): Boolean = other is Deferred && link == other.link && reason == other.reason
        override fun hashCode(): Int = link.hashCode() * 31 + reason.hashCode()
        override fun toString(): String = "Deferred(link=$link, reason=$reason)"
    }

    /** 인터셉터·라우터가 예외를 던졌다. 원래 예외는 [cause] 의 `cause` 다. */
    public class Failed(public val link: ResolvedDeepLink, public val cause: DeepLinkException) : DeepLinkOutcome {
        override fun equals(other: Any?): Boolean = other is Failed && link == other.link && cause == other.cause
        override fun hashCode(): Int = link.hashCode() * 31 + cause.hashCode()
        override fun toString(): String = "Failed(link=$link, cause=$cause)"
    }
}

/**
 * 인터셉터·라우터 같은 소비자 구현이 실패했거나, 자기 링크 서명 키를 준비하지 못했음을 알리는 예외. 원래 예외를 [cause] 로 보존한다.
 * 메시지에는 경로 id 만 들어간다.
 */
public class DeepLinkException(message: String, cause: Throwable?) : RuntimeException(message, cause)
