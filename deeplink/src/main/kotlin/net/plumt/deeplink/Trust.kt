package net.plumt.deeplink

/**
 * 발신자 신뢰 수준. [PUBLIC] < [FIRST_PARTY] < [SELF] 순으로 강하다.
 *
 * 값이 늘 수 있어 열거형이 아니라 상수를 가진 클래스다 — `when` 으로 가를 때는 `else` 를 둔다. 강약은 [satisfies] 로 비교한다.
 */
public class TrustLevel private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    private val rank: Int,
) {

    /** 이 수준이 [required] 를 충족하는지. */
    public fun satisfies(required: TrustLevel): Boolean = rank >= required.rank

    override fun toString(): String = name

    public companion object {
        /** 누구나. 민감한 동작이 없는 화면. */
        @JvmField
        public val PUBLIC: TrustLevel = TrustLevel("PUBLIC", 0)

        /** 서명까지 확인한 신뢰 목록의 앱. 자사 앱 연동. */
        @JvmField
        public val FIRST_PARTY: TrustLevel = TrustLevel("FIRST_PARTY", 10)

        /** 자기 앱이 의도해서 발급한 링크. 알림 탭·앱 안 이동. */
        @JvmField
        public val SELF: TrustLevel = TrustLevel("SELF", 20)
    }
}

/**
 * 발신자 정보를 무엇으로 알아냈는가. 신뢰의 근거는 패키지 이름이 아니라 **누가 그 이름을 보증했는가**다.
 *
 * 값끼리 크기를 비교하지 않는다. 신뢰 수준은 [TrustPolicy] 가 값마다 정한다.
 * 직접 구현한 [CallerVerifier] 는 생성자로 새 근거(예: 서버가 서명한 토큰)를 만들고, 직접 구현한 [TrustPolicy] 가 그 값을 판정한다.
 * 같은 [name] 이면 같은 값이다. 값이 늘 수 있으므로 `when` 으로 가를 때는 `else` 를 둔다.
 *
 * @property name 근거 이름. 영문 대문자·숫자·`_` 1~64자
 * @throws IllegalArgumentException [name] 형식이 틀렸을 때
 */
public class CallerVerification(public val name: String) {
    init {
        require(NAME.matches(name)) { "verification 이름은 영문 대문자·숫자·_ 1~64자여야 한다: '$name'" }
    }

    override fun equals(other: Any?): Boolean = other is CallerVerification && name == other.name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        private val NAME = Regex("[A-Z][A-Z0-9_]{0,63}")

        /** 발신자를 모른다(브라우저·알림 등 OS 경유, 또는 정보 없음). */
        @JvmField
        public val NONE: CallerVerification = CallerVerification("NONE")

        /**
         * 확인할 수 없는 값이다. 인텐트 extras 의 referrer 와 `Activity.getReferrer()` 가 여기에 든다 —
         * 플랫폼 문서가 "위조할 수 있으니 보안 판단에 쓰지 말라" 고 적은 값이다. 참고·집계용으로만 남긴다.
         */
        @JvmField
        public val CLAIMED: CallerVerification = CallerVerification("CLAIMED")

        /** OS 가 발신 uid 로 보증한 패키지다(Android 14+ 의 launchedFromUid 와 그 uid 의 패키지 대조). */
        @JvmField
        public val OS_VERIFIED: CallerVerification = CallerVerification("OS_VERIFIED")

        /** [OS_VERIFIED] 인 패키지이고, 그 패키지의 서명 인증서가 허용 목록과 맞는다. */
        @JvmField
        public val SIGNATURE_VERIFIED: CallerVerification = CallerVerification("SIGNATURE_VERIFIED")

        /**
         * 이 앱이 스스로 발급한 링크임을 앱만 가진 비밀(서명 토큰)로 증명했다. 앱이 **의도해서** 만든 링크만
         * 이 수준이 된다 — 앱이 외부에서 받은 링크를 그대로 열어(중계) 생긴 인텐트는 여기에 들지 않는다.
         */
        @JvmField
        public val SELF_ISSUED: CallerVerification = CallerVerification("SELF_ISSUED")
    }
}

/**
 * 발신자 정보. [CallerVerifier] 가 만들거나, 테스트에서는 호출부가 직접 만든다.
 *
 * @property packageName 발신 앱 식별자. 모르면 `null`
 * @property verification [packageName] 을 얼마나 확실히 알아냈는가
 * @throws IllegalArgumentException [packageName] 이 빈 문자열이거나, `null` 인데 [verification] 이 `NONE` 이 아닐 때
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

/** 발신자 정보를 신뢰 수준으로 바꾸는 정책. 교체해 조직 고유 규칙을 넣는다([DeepLinkConfig.Builder.trustPolicy]). */
public fun interface TrustPolicy {

    /** [caller] 의 신뢰 수준. */
    public fun resolve(caller: CallerIdentity): TrustLevel

    public companion object {
        /**
         * 기본 정책. [DeepLinks.create] 는 정책을 정하지 않으면 자기 패키지로 이 정책을 쓴다.
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
