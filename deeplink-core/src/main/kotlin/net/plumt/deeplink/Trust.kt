package net.plumt.deeplink

/**
 * 발신자 신뢰 수준. 아래로 갈수록 강하다.
 *
 * 강약은 선언 순서가 아니라 [rank] 로 비교하므로 상수를 재배열해도 판정이 뒤집히지 않는다.
 */
public enum class TrustLevel(internal val rank: Int) {
    /** 누구나. 민감한 동작이 없는 화면. */
    PUBLIC(0),

    /** 서명까지 확인한 신뢰 목록의 앱. 자사 앱 연동. */
    FIRST_PARTY(10),

    /** 자기 앱이 의도해서 발급한 링크. 알림 탭·앱 안 이동. */
    SELF(20),
    ;

    /** 이 수준이 [required] 를 충족하는지. */
    public fun satisfies(required: TrustLevel): Boolean = rank >= required.rank
}

/**
 * 발신자 정보를 무엇으로 알아냈는가. 신뢰의 근거는 패키지 이름이 아니라 **누가 그 이름을 보증했는가**다.
 *
 * 상수는 서로 크기를 비교하지 않는다. 신뢰 수준은 [TrustPolicy] 가 상수마다 정한다.
 */
public enum class CallerVerification {
    /** 발신자를 모른다(브라우저·알림 등 OS 경유, 또는 정보 없음). */
    NONE,

    /**
     * 확인할 수 없는 값이다. 인텐트 extras 의 referrer 와 `Activity.getReferrer()` 가 여기에 든다 —
     * 플랫폼 문서가 "위조할 수 있으니 보안 판단에 쓰지 말라" 고 적은 값이다. 참고·집계용으로만 남긴다.
     */
    CLAIMED,

    /** OS 가 발신 uid 로 보증한 패키지다(Android 14+ 의 launchedFromUid 와 그 uid 의 패키지 대조). */
    OS_VERIFIED,

    /** [OS_VERIFIED] 인 패키지이고, 그 패키지의 서명 인증서가 허용 목록과 맞는다. */
    SIGNATURE_VERIFIED,

    /**
     * 이 앱이 스스로 발급한 링크임을 앱만 가진 비밀(서명 토큰)로 증명했다. 앱이 **의도해서** 만든 링크만
     * 이 수준이 된다 — 앱이 외부에서 받은 링크를 그대로 열어(중계) 생긴 인텐트는 여기에 들지 않는다.
     */
    SELF_ISSUED,
}

/**
 * 발신자 정보. 플랫폼 어댑터(Android 모듈의 `CallerVerifier`)가 만들거나, 서버·데스크톱에서는
 * 호출부가 직접 만든다.
 *
 * @property packageName 발신 앱 식별자. 모르면 `null`
 * @property verification [packageName] 을 얼마나 확실히 알아냈는가
 */
public class CallerIdentity(
    public val packageName: String?,
    public val verification: CallerVerification,
) {
    init {
        require(packageName == null || packageName.isNotBlank()) { "packageName 은 null 이거나 비어 있지 않아야 한다: '$packageName'" }
        require(packageName != null || verification == CallerVerification.NONE) {
            "packageName 이 null 이면 verification 은 NONE 이어야 한다: $verification"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CallerIdentity && packageName == other.packageName && verification == other.verification

    override fun hashCode(): Int = 31 * (packageName?.hashCode() ?: 0) + verification.hashCode()

    override fun toString(): String = "CallerIdentity(packageName=$packageName, verification=$verification)"

    public companion object {
        /** 발신자를 모름. */
        @JvmField
        public val UNKNOWN: CallerIdentity = CallerIdentity(null, CallerVerification.NONE)
    }
}

/** 발신자 정보를 신뢰 수준으로 바꾸는 정책. 교체해 조직 고유 규칙을 넣을 수 있다. */
public fun interface TrustPolicy {

    /** [caller] 의 신뢰 수준. */
    public fun resolve(caller: CallerIdentity): TrustLevel

    public companion object {
        /**
         * 기본 정책.
         *
         * - [CallerVerification.SELF_ISSUED] 이고 [selfPackage] 와 같음 → [TrustLevel.SELF]
         * - [CallerVerification.SIGNATURE_VERIFIED] 이고 [selfPackage] 가 아님 → [TrustLevel.FIRST_PARTY]
         * - 그 밖 → [TrustLevel.PUBLIC]. 주장만 한 값([CallerVerification.CLAIMED])은 물론이고, OS 가 보증한
         *   자기 패키지([CallerVerification.OS_VERIFIED])도 PUBLIC 이다 — 자기 앱이 외부(채팅 상대·웹 페이지·
         *   푸시 페이로드)에서 받은 링크를 그대로 열었을 수 있어서다(혼동된 대리인)
         *
         * URI 가 https(App Link)라는 사실은 신뢰 근거로 치지 않는다. 아무 앱이나 같은 URI 로 인텐트를
         * 직접 보낼 수 있기 때문이다.
         *
         * @param selfPackage 자기 앱 식별자. `null` 이면 SELF 판정을 하지 않는다
         * @throws IllegalArgumentException [selfPackage] 가 빈 문자열일 때
         */
        @JvmStatic
        public fun standard(selfPackage: String?): TrustPolicy {
            require(selfPackage == null || selfPackage.isNotBlank()) { "selfPackage 는 null 이거나 비어 있지 않아야 한다: '$selfPackage'" }
            return TrustPolicy { caller ->
                val isSelf = selfPackage != null && caller.packageName == selfPackage
                when {
                    isSelf && caller.verification == CallerVerification.SELF_ISSUED -> TrustLevel.SELF
                    !isSelf && caller.verification == CallerVerification.SIGNATURE_VERIFIED -> TrustLevel.FIRST_PARTY
                    else -> TrustLevel.PUBLIC
                }
            }
        }
    }
}
