package net.plumt.deeplink

/**
 * 이 앱의 https App Link 검증 상태([DeepLinks.appLinkStatus]). 닫힌 집합이라 `when` 에 `else` 가 필요 없다.
 *
 * App Link 가 검증되지 않으면 그 https 링크는 앱이 아니라 브라우저(또는 선택 대화상자)로 열린다. 검증돼 있어도 아무 앱이나
 * 같은 URI 로 인텐트를 직접 보낼 수 있으므로, 검증 상태는 신뢰 근거가 아니다 — 링크가 앱으로 오는지만 알려 준다.
 */
public sealed interface AppLinkStatus {

    /**
     * Android 12(API 31) 미만이다. 그 아래에는 검증 결과를 묻는 공개 API 가 없다(설치 때 한 번 검증하고 앱에 알리지 않는다).
     */
    public object Unsupported : AppLinkStatus {
        override fun toString(): String = "Unsupported"
    }

    /** 플랫폼이 상태를 주지 않았다(시스템 서비스 없음·패키지 조회 실패). 다시 물으면 바뀔 수 있다. */
    public object Unavailable : AppLinkStatus {
        override fun toString(): String = "Unavailable"
    }

    /**
     * 플랫폼이 준 상태.
     *
     * @property linkHandlingAllowed 사용자가 설정의 "지원되는 링크 열기" 를 켜 두었는지. 꺼져 있으면 검증된 호스트도 앱으로 오지 않는다
     * @property hosts 매니페스트의 `autoVerify` 인텐트 필터에 적은 호스트 → 상태
     */
    public class Available(
        public val linkHandlingAllowed: Boolean,
        public val hosts: Map<String, AppLinkHostState>,
    ) : AppLinkStatus {

        /** [host] 가 `assetlinks.json` 으로 검증됐는지. 매니페스트에 없는 호스트는 `false`. */
        public fun isVerified(host: String): Boolean = hosts[host] == AppLinkHostState.VERIFIED

        /** [host] 링크가 지금 이 앱으로 바로 열리는지 — 사용자 허용이 켜져 있고 검증됐거나 사용자가 고른 호스트. */
        public fun opensInApp(host: String): Boolean {
            val state = hosts[host]
            return linkHandlingAllowed && (state == AppLinkHostState.VERIFIED || state == AppLinkHostState.SELECTED)
        }

        override fun equals(other: Any?): Boolean =
            other is Available && linkHandlingAllowed == other.linkHandlingAllowed && hosts == other.hosts

        override fun hashCode(): Int = linkHandlingAllowed.hashCode() * 31 + hosts.hashCode()

        override fun toString(): String = "Available(linkHandlingAllowed=$linkHandlingAllowed, hosts=$hosts)"
    }
}

/**
 * App Link 호스트 하나의 상태. 플랫폼 상태가 늘 수 있어 열거형이 아니다 — `when` 으로 가를 때는 `else` 를 둔다.
 * 플랫폼이 이 라이브러리가 모르는 값을 주면 [NONE] 으로 본다(앱으로 열린다고 가정하지 않는다).
 */
public class AppLinkHostState private constructor(
    /** 상수 이름. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /** 검증되지 않았고 사용자가 고르지도 않았다. 링크는 브라우저·선택 대화상자로 간다. `assetlinks.json` 을 확인한다. */
        @JvmField
        public val NONE: AppLinkHostState = AppLinkHostState("NONE")

        /** 검증되지 않았지만 사용자가 설정에서 이 앱으로 열기를 골랐다. */
        @JvmField
        public val SELECTED: AppLinkHostState = AppLinkHostState("SELECTED")

        /** `assetlinks.json` 으로 검증됐다. */
        @JvmField
        public val VERIFIED: AppLinkHostState = AppLinkHostState("VERIFIED")
    }
}
