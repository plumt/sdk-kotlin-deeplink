package net.plumt.deeplink.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import net.plumt.deeplink.DeepLinkRequest

/** [DeepLinkIntentReader.read] 결과. */
public sealed class IntentReadResult {

    /** 딥링크다. [request] 를 `DeepLinkHandler.handle` 에 넘긴다. */
    public data class Link(val request: DeepLinkRequest) : IntentReadResult()

    /** 딥링크가 아니다(허용하지 않은 액션이거나 data 가 없다). 평소 시작 흐름을 탄다. */
    public data object NotDeepLink : IntentReadResult()

    /** 적대적으로 보이는 인텐트다. 아무것도 열지 않고 평소 시작 흐름으로 보낸다. */
    public data class Unsafe(val reason: UnsafeIntentReason) : IntentReadResult()
}

/** [IntentReadResult.Unsafe] 의 사유. */
public enum class UnsafeIntentReason {
    /** 셀렉터가 붙어 있다. 받은 인텐트에 셀렉터가 있을 이유가 없고, 되넘기면 엉뚱한 컴포넌트가 열린다. */
    HAS_SELECTOR,

    /** extras 를 풀 수 없다(알 수 없는 Parcelable·손상된 Bundle). */
    UNREADABLE_EXTRAS,
}

/**
 * Activity 가 받은 인텐트를 [DeepLinkRequest] 로 바꾼다.
 *
 * - data URI 문자열과 발신자([CallerVerifier]) **만** 꺼낸다. extras 의 Intent·PendingIntent·URL 은
 *   어떤 경로로도 꺼내 주지 않는다 — 인텐트 리다이렉션의 재료를 아예 넘기지 않는다
 * - extras 가 꼭 필요하면 [SafeExtras] 로 원시 타입만 읽는다
 *
 * 메인 스레드에서 부른다.
 */
public class DeepLinkIntentReader private constructor(
    private val callerVerifier: CallerVerifier,
    private val allowedActions: Set<String>,
) {

    /** [activity] 가 받은 [intent](기본 `activity.intent`)를 읽는다. `onNewIntent` 에서는 받은 인텐트를 넘긴다. */
    @JvmOverloads
    public fun read(activity: Activity, intent: Intent? = activity.intent): IntentReadResult {
        if (intent == null || intent.action !in allowedActions) return IntentReadResult.NotDeepLink
        val data = intent.dataString ?: return IntentReadResult.NotDeepLink
        if (intent.selector != null) return IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR)
        if (!extrasReadable(intent)) return IntentReadResult.Unsafe(UnsafeIntentReason.UNREADABLE_EXTRAS)
        return IntentReadResult.Link(DeepLinkRequest(data, callerVerifier.identify(activity, intent)))
    }

    private fun extrasReadable(intent: Intent): Boolean = try {
        // size() 가 Bundle 을 풀게 만든다. 손상된 Parcel 은 여기서 예외가 난다
        intent.extras?.size()
        true
    } catch (_: RuntimeException) {
        // 풀 수 없는 extras 는 Unsafe 결과로 알린다
        false
    }

    public companion object {
        /**
         * @param trustedCallers 서명까지 확인해 FIRST_PARTY 로 올릴 앱([CallerVerifier.create] 참고)
         * @param allowedActions 딥링크로 볼 인텐트 액션. 기본 `ACTION_VIEW` 하나
         * @throws IllegalArgumentException [allowedActions] 가 비었을 때
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            trustedCallers: Collection<TrustedCaller> = emptyList(),
            allowedActions: Set<String> = setOf(Intent.ACTION_VIEW),
        ): DeepLinkIntentReader = create(CallerVerifier.create(context, trustedCallers), allowedActions)

        /**
         * 발신자 판정을 직접 구현한 [CallerVerifier] 로 바꾼다.
         *
         * @throws IllegalArgumentException [allowedActions] 가 비었을 때
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            callerVerifier: CallerVerifier,
            allowedActions: Set<String> = setOf(Intent.ACTION_VIEW),
        ): DeepLinkIntentReader {
            require(allowedActions.isNotEmpty()) { "allowedActions 가 비었다" }
            return DeepLinkIntentReader(callerVerifier, allowedActions.toSet())
        }
    }
}
