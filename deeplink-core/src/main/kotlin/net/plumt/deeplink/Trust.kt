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

    /** 자기 앱. 알림 탭·앱 안 이동. */
    SELF(20),
    ;

    /** 이 수준이 [required] 를 충족하는지. */
    public fun satisfies(required: TrustLevel): Boolean = rank >= required.rank
}

/**
 * 발신자를 얼마나 확실히 알아냈는가. 아래로 갈수록 확실하다.
 *
 * 패키지 이름만으로는 아무것도 증명되지 않는다. 같은 이름이라도 **누가 그 이름을 알려 줬는가**가
 * 신뢰의 근거다.
 */
public enum class CallerVerification {
    /** 발신자를 모른다(브라우저·알림 등 OS 경유, 또는 정보 없음). */
    NONE,

    /** 발신자가 스스로 주장한 값이다(예: 인텐트 extras 의 referrer). 위조할 수 있다. */
    CLAIMED,

    /** OS 가 채운 referrer 에서 얻었다. 발신 앱이 직접 바꿀 수 없다. */
    SYSTEM_REPORTED,

    /** OS 가 발신 uid 로 확인한 패키지다(Android 14+ 의 launchedFrom 정보). */
    OS_VERIFIED,

    /** OS 가 알려 준 패키지이고, 그 패키지의 서명 인증서가 허용 목록과 맞는다. */
    SIGNATURE_VERIFIED,
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
         * - [selfPackage] 와 같고 [CallerVerification.SYSTEM_REPORTED] 이상 → [TrustLevel.SELF]
         * - [CallerVerification.SIGNATURE_VERIFIED] → [TrustLevel.FIRST_PARTY]
         * - 그 밖(주장만 한 값 포함) → [TrustLevel.PUBLIC]
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
                val reported = caller.verification >= CallerVerification.SYSTEM_REPORTED
                when {
                    selfPackage != null && reported && caller.packageName == selfPackage -> TrustLevel.SELF
                    caller.verification == CallerVerification.SIGNATURE_VERIFIED -> TrustLevel.FIRST_PARTY
                    else -> TrustLevel.PUBLIC
                }
            }
        }
    }
}
