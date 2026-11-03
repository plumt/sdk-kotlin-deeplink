package net.plumt.deeplink

/**
 * 처리할 딥링크 하나.
 *
 * [toString] 은 URI 를 보이지 않는다(토큰이 섞여 있을 수 있다).
 *
 * @property uri 받은 URI 문자열 그대로
 * @property caller 발신자. 모르면 [CallerIdentity.UNKNOWN]
 */
public class DeepLinkRequest @JvmOverloads constructor(
    public val uri: String,
    public val caller: CallerIdentity = CallerIdentity.UNKNOWN,
) {
    override fun toString(): String = "DeepLinkRequest(uriLength=${uri.length}, caller=$caller)"
}

/** [DeepLinkHandler.resolve] 결과. */
public sealed class DeepLinkResolution {
    /** 통과. */
    public data class Resolved(val link: ResolvedDeepLink) : DeepLinkResolution()

    /** 거부. */
    public data class Rejected(val rejection: Rejection) : DeepLinkResolution()
}

/**
 * [DeepLinkHandler.handle] 의 최종 결과. 실패도 예외가 아니라 이 값으로 온다
 * (취소 `CancellationException` 과 `Error` 만 그대로 전파된다).
 */
public sealed class DeepLinkOutcome {

    /** 라우터가 이동을 처리했다. */
    public data class Handled(val link: ResolvedDeepLink) : DeepLinkOutcome()

    /** 검증은 통과했지만 라우터가 `false` 를 돌려줬다. 호출부가 기본 화면으로 보낸다. */
    public data class Unhandled(val link: ResolvedDeepLink) : DeepLinkOutcome()

    /** 검증이나 인터셉터에서 거부됐다. */
    public data class Rejected(val rejection: Rejection) : DeepLinkOutcome()

    /**
     * 인터셉터가 처리를 미뤘다(예: 로그인 필요). 조건이 갖춰지면 [DeepLinkHandler.resume] 에 [link] 를
     * 넘겨 다시 진행한다. [link] 는 메모리에만 두고 디스크에 쓰지 않는다.
     */
    public data class Deferred(val link: ResolvedDeepLink, val reason: String) : DeepLinkOutcome()

    /** 인터셉터·라우터가 예외를 던졌다. 원래 예외는 [cause] 의 `cause` 다. */
    public data class Failed(val link: ResolvedDeepLink, val cause: DeepLinkException) : DeepLinkOutcome()
}

/**
 * 인터셉터·라우터 같은 소비자 구현이 실패했음을 알리는 예외. 원래 예외를 [cause] 로 보존한다.
 * 메시지에는 경로 id 만 들어간다.
 */
public class DeepLinkException(message: String, cause: Throwable?) : RuntimeException(message, cause)
